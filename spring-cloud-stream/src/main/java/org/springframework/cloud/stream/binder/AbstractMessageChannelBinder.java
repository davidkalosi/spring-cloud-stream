/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder;


import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.cloud.stream.provisioning.ProvisioningProvider;
import org.springframework.context.Lifecycle;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.channel.FixedSubscriberChannel;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.endpoint.EventDrivenConsumer;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.handler.AbstractReplyProducingMessageHandler;
import org.springframework.integration.handler.BridgeHandler;
import org.springframework.integration.handler.advice.ErrorMessageSendingRecoverer;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * {@link AbstractBinder} that serves as base class for {@link MessageChannel} binders.
 * Implementors must implement the following methods:
 * <ul>
 * <li>{@link #createProducerMessageHandler(ProducerDestination, ProducerProperties)}</li>
 * <li>{@link #createConsumerEndpoint(ConsumerDestination, String, ConsumerProperties)}
 * </li>
 * </ul>
 *
 * @param <C> the consumer properties type
 * @param <P> the producer properties type
 * @author Marius Bogoevici
 * @author Ilayaperumal Gopinathan
 * @author Soby Chacko
 * @since 1.1
 */
public abstract class AbstractMessageChannelBinder<C extends ConsumerProperties, P extends ProducerProperties, PP extends ProvisioningProvider<C, P>>
		extends AbstractBinder<MessageChannel, C, P> {

	protected static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

	/**
	 * {@link ProvisioningProvider} delegated by the downstream binder implementations.
	 */
	protected final PP provisioningProvider;

	/**
	 * Indicates whether the implementation and the message broker have native support for
	 * message headers. If false, headers will be embedded in the message payloads.
	 */
	private final boolean supportsHeadersNatively;

	/**
	 * Indicates what headers are to be embedded in the payload if
	 * {@link #supportsHeadersNatively} is true.
	 */
	private final String[] headersToEmbed;

	public AbstractMessageChannelBinder(boolean supportsHeadersNatively, String[] headersToEmbed,
			PP provisioningProvider) {
		this.supportsHeadersNatively = supportsHeadersNatively;
		this.headersToEmbed = headersToEmbed == null ? new String[0] : headersToEmbed;
		this.provisioningProvider = provisioningProvider;
	}

	/**
	 * Binds an outbound channel to a given destination. The implementation delegates to
	 * {@link ProvisioningProvider#provisionProducerDestination(String, ProducerProperties)}
	 * and {@link #createProducerMessageHandler(ProducerDestination, ProducerProperties)}
	 * for handling the middleware specific logic. If the returned producer message
	 * handler is an {@link InitializingBean} then
	 * {@link InitializingBean#afterPropertiesSet()} will be called on it. Similarly, if
	 * the returned producer message handler e ndpoint is a {@link Lifecycle}, then
	 * {@link Lifecycle#start()} will be called on it.
	 *
	 * @param destination the name of the destination
	 * @param outputChannel the channel to be bound
	 * @param producerProperties the {@link ProducerProperties} of the binding
	 * @return the Binding for the channel
	 * @throws BinderException on internal errors during binding
	 */
	@Override
	public final Binding<MessageChannel> doBindProducer(final String destination, MessageChannel outputChannel,
			final P producerProperties) throws BinderException {
		Assert.isInstanceOf(SubscribableChannel.class, outputChannel,
				"Binding is supported only for SubscribableChannel instances");
		final MessageHandler producerMessageHandler;
		final ProducerDestination producerDestination;
		try {
			producerDestination = this.provisioningProvider.provisionProducerDestination(destination,
					producerProperties);
			SubscribableChannel errorChannel = producerProperties.isErrorChannelEnabled()
					? registerErrorInfrastructure(producerDestination) : null;
			producerMessageHandler = createProducerMessageHandler(producerDestination, producerProperties,
					errorChannel);
			if (producerMessageHandler instanceof InitializingBean) {
				((InitializingBean) producerMessageHandler).afterPropertiesSet();
			}
		}
		catch (Exception e) {
			if (e instanceof BinderException) {
				throw (BinderException) e;
			}
			else if (e instanceof ProvisioningException) {
				throw (ProvisioningException) e;
			}
			else {
				throw new BinderException("Exception thrown while building outbound endpoint", e);
			}
		}
		if (producerMessageHandler instanceof Lifecycle) {
			((Lifecycle) producerMessageHandler).start();
		}
		((SubscribableChannel) outputChannel).subscribe(
				new SendingHandler(producerMessageHandler, !this.supportsHeadersNatively && HeaderMode.embeddedHeaders
						.equals(producerProperties.getHeaderMode()), this.headersToEmbed,
						producerProperties.isUseNativeEncoding()));

		return new DefaultBinding<MessageChannel>(destination, null, outputChannel,
				producerMessageHandler instanceof Lifecycle ? (Lifecycle) producerMessageHandler : null) {

			@Override
			public void afterUnbind() {
				try {
					destroyErrorInfrastructure(producerDestination);
					if (producerMessageHandler instanceof DisposableBean) {
						((DisposableBean) producerMessageHandler).destroy();
					}
				}
				catch (Exception e) {
					AbstractMessageChannelBinder.this.logger
							.error("Exception thrown while unbinding " + this.toString(), e);
				}
				afterUnbindProducer(producerDestination, producerProperties);
			}
		};
	}

	/**
	 * Creates a {@link MessageHandler} with the ability to send data to the target
	 * middleware. If the returned instance is also a {@link Lifecycle}, it will be
	 * stopped automatically by the binder.
	 * <p>
	 * In order to be fully compliant, the {@link MessageHandler} of the binder must
	 * observe the following headers:
	 * <ul>
	 * <li>{@link BinderHeaders#PARTITION_HEADER} - indicates the target partition where
	 * the message must be sent</li>
	 * </ul>
	 * <p>
	 *
	 * @param destination the name of the target destination
	 * @param producerProperties the producer properties
	 * @param errorChannel the error channel (if enabled, otherwise null). If not null,
	 * the binder must wire this channel into the producer endpoint so that errors
	 * are forwarded to it.
	 * @return the message handler for sending data to the target middleware
	 * @throws Exception
	 */
	protected abstract MessageHandler createProducerMessageHandler(ProducerDestination destination,
			P producerProperties, MessageChannel errorChannel)
			throws Exception;

	/**
	 * Invoked after the unbinding of a producer. Subclasses may override this to provide
	 * their own logic for dealing with unbinding.
	 *
	 * @param destination the bound destination
	 * @param producerProperties the producer properties
	 */
	protected void afterUnbindProducer(ProducerDestination destination, P producerProperties) {
	}

	/**
	 * Binds an inbound channel to a given destination. The implementation delegates to
	 * {@link ProvisioningProvider#provisionConsumerDestination(String, String, ConsumerProperties)}
	 * and
	 * {@link #createConsumerEndpoint(ConsumerDestination, String, ConsumerProperties)}
	 * for handling middleware-specific logic. If the returned consumer endpoint is an
	 * {@link InitializingBean} then {@link InitializingBean#afterPropertiesSet()} will be
	 * called on it. Similarly, if the returned consumer endpoint is a {@link Lifecycle},
	 * then {@link Lifecycle#start()} will be called on it.
	 *
	 * @param name the name of the destination
	 * @param group the consumer group
	 * @param inputChannel the channel to be bound
	 * @param properties the {@link ConsumerProperties} of the binding
	 * @return the Binding for the channel
	 * @throws BinderException on internal errors during binding
	 */
	@Override
	public final Binding<MessageChannel> doBindConsumer(String name, String group, MessageChannel inputChannel,
			final C properties) throws BinderException {
		MessageProducer consumerEndpoint = null;
		try {
			final ConsumerDestination destination = this.provisioningProvider.provisionConsumerDestination(name, group,
					properties);
			final boolean extractEmbeddedHeaders = HeaderMode.embeddedHeaders.equals(
					properties.getHeaderMode()) && !this.supportsHeadersNatively;
			ReceivingHandler rh = new ReceivingHandler(extractEmbeddedHeaders);
			rh.setOutputChannel(inputChannel);
			final FixedSubscriberChannel bridge = new FixedSubscriberChannel(rh);
			bridge.setBeanName("bridge." + name);
			consumerEndpoint = createConsumerEndpoint(destination, group, properties);
			consumerEndpoint.setOutputChannel(bridge);
			if (consumerEndpoint instanceof InitializingBean) {
				((InitializingBean) consumerEndpoint).afterPropertiesSet();
			}
			if (consumerEndpoint instanceof Lifecycle) {
				((Lifecycle) consumerEndpoint).start();
			}
			final Object endpoint = consumerEndpoint;
			EventDrivenConsumer edc = new EventDrivenConsumer(bridge, rh);
			edc.setBeanName("inbound." + groupedName(name, group));
			edc.start();
			return new DefaultBinding<MessageChannel>(name, group, inputChannel,
					endpoint instanceof Lifecycle ? (Lifecycle) endpoint : null) {

				@Override
				protected void afterUnbind() {
					try {
						if (endpoint instanceof DisposableBean) {
							((DisposableBean) endpoint).destroy();
						}
					}
					catch (Exception e) {
						AbstractMessageChannelBinder.this.logger
								.error("Exception thrown while unbinding " + this.toString(), e);
					}
					afterUnbindConsumer(destination, this.group, properties);
					destroyErrorInfrastructure(destination, group, properties);
				}

			};
		}
		catch (Exception e) {
			if (consumerEndpoint instanceof Lifecycle) {
				((Lifecycle) consumerEndpoint).stop();
			}
			if (e instanceof BinderException) {
				throw (BinderException) e;
			}
			else if (e instanceof ProvisioningException) {
				throw (ProvisioningException) e;
			}
			else {
				throw new BinderException("Exception thrown while starting consumer: ", e);
			}
		}
	}

	/**
	 * Creates {@link MessageProducer} that receives data from the consumer destination.
	 * will be started and stopped by the binder.
	 *
	 * @param group the consumer group
	 * @param destination reference to the consumer destination
	 * @param properties the consumer properties
	 * @return the consumer endpoint.
	 */
	protected abstract MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group,
			C properties) throws Exception;

	/**
	 * Invoked after the unbinding of a consumer. The binder implementation can override
	 * this method to provide their own logic (e.g. for cleaning up destinations).
	 *
	 * @param destination the consumer destination
	 * @param group the consumer group
	 * @param consumerProperties the consumer properties
	 */
	protected void afterUnbindConsumer(ConsumerDestination destination, String group, C consumerProperties) {
	}

	/**
	 * Register an error channel for the destination when an async send error is received.
	 * Bridge the channel to the global error channel (if present).
	 * @param destination the destination.
	 * @return the channel.
	 */
	private SubscribableChannel registerErrorInfrastructure(ProducerDestination destination) {
		ConfigurableListableBeanFactory beanFactory = getApplicationContext().getBeanFactory();
		String errorChannelName = errorsBaseName(destination);
		SubscribableChannel errorChannel = null;
		if (getApplicationContext().containsBean(errorChannelName)) {
			Object errorChannelObject = getApplicationContext().getBean(errorChannelName);
			if (!(errorChannelObject instanceof SubscribableChannel)) {
				throw new IllegalStateException(
						"Error channel '" + errorChannelName + "' must be a SubscribableChannel");
			}
			errorChannel = (SubscribableChannel) errorChannelObject;
		}
		else {
			errorChannel = new PublishSubscribeChannel();
			beanFactory.registerSingleton(errorChannelName, errorChannel);
			errorChannel = (PublishSubscribeChannel) beanFactory.initializeBean(errorChannel, errorChannelName);
		}
		MessageChannel defaultErrorChannel = null;
		if (getApplicationContext().containsBean(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME)) {
			defaultErrorChannel = getApplicationContext().getBean(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME,
					MessageChannel.class);
		}
		if (defaultErrorChannel != null) {
			BridgeHandler errorBridge = new BridgeHandler();
			errorBridge.setOutputChannel(defaultErrorChannel);
			errorChannel.subscribe(errorBridge);
			String errorBridgeHandlerName = getErrorBridgeName(destination);
			beanFactory.registerSingleton(errorBridgeHandlerName, errorBridge);
			beanFactory.initializeBean(errorBridge, errorBridgeHandlerName);
		}
		return errorChannel;
	}

	/**
	 * Build an errorChannelRecoverer that writes to a pub/sub channel for the destination
	 * when an exception is thrown to a consumer.
	 * @param destination the destination.
	 * @param group the group.
	 * @param consumerProperties the properties.
	 * @return the ErrorInfrastructure which is a holder for the error channel, the recoverer and the
	 * message handler that is subscribed to the channel.
	 */
	protected final ErrorInfrastructure registerErrorInfrastructure(ConsumerDestination destination, String group,
			C consumerProperties) {
		ErrorMessageStrategy errorMessageStrategy = getErrorMessageStrategy();
		ConfigurableListableBeanFactory beanFactory = getApplicationContext().getBeanFactory();
		String errorChannelName = errorsBaseName(destination, group, consumerProperties);
		SubscribableChannel errorChannel = null;
		if (getApplicationContext().containsBean(errorChannelName)) {
			Object errorChannelObject = getApplicationContext().getBean(errorChannelName);
			if (!(errorChannelObject instanceof SubscribableChannel)) {
				throw new IllegalStateException(
						"Error channel '" + errorChannelName + "' must be a SubscribableChannel");
			}
			errorChannel = (SubscribableChannel) errorChannelObject;
		}
		else {
			errorChannel = new BinderErrorChannel();
			beanFactory.registerSingleton(errorChannelName, errorChannel);
			errorChannel = (LastSubscriberAwareChannel) beanFactory.initializeBean(errorChannel, errorChannelName);
		}
		ErrorMessageSendingRecoverer recoverer;
		if (errorMessageStrategy == null) {
			recoverer = new ErrorMessageSendingRecoverer(errorChannel);
		}
		else {
			recoverer = new ErrorMessageSendingRecoverer(errorChannel, errorMessageStrategy);
		}
		String recovererBeanName = getErrorRecovererName(destination, group, consumerProperties);
		beanFactory.registerSingleton(recovererBeanName, recoverer);
		beanFactory.initializeBean(recoverer, recovererBeanName);
		MessageHandler handler = getErrorMessageHandler(destination, group, consumerProperties);
		MessageChannel defaultErrorChannel = null;
		if (getApplicationContext().containsBean(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME)) {
			defaultErrorChannel = getApplicationContext().getBean(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME,
					MessageChannel.class);
		}
		if (handler == null && errorChannel instanceof LastSubscriberAwareChannel) {
			handler = getDefaultErrorMessageHandler((LastSubscriberAwareChannel) errorChannel, defaultErrorChannel != null);
		}
		String errorMessageHandlerName = getErrorMessageHandlerName(destination, group, consumerProperties);
		if (handler != null) {
			beanFactory.registerSingleton(errorMessageHandlerName, handler);
			beanFactory.initializeBean(handler, errorMessageHandlerName);
			errorChannel.subscribe(handler);
		}
		if (defaultErrorChannel != null) {
			BridgeHandler errorBridge = new BridgeHandler();
			errorBridge.setOutputChannel(defaultErrorChannel);
			errorChannel.subscribe(errorBridge);
			String errorBridgeHandlerName = getErrorBridgeName(destination, group, consumerProperties);
			beanFactory.registerSingleton(errorBridgeHandlerName, errorBridge);
			beanFactory.initializeBean(errorBridge, errorBridgeHandlerName);
		}
		return new ErrorInfrastructure(errorChannel, recoverer, handler);
	}

	private void destroyErrorInfrastructure(ProducerDestination destination) {
		String errorChannelName = errorsBaseName(destination);
		String errorBridgeHandlerName = getErrorBridgeName(destination);
		MessageHandler bridgeHandler = null;
		if (getApplicationContext().containsBean(errorBridgeHandlerName)) {
			bridgeHandler = getApplicationContext().getBean(errorBridgeHandlerName, MessageHandler.class);
		}
		if (getApplicationContext().containsBean(errorChannelName)) {
			SubscribableChannel channel = getApplicationContext().getBean(errorChannelName, SubscribableChannel.class);
			if (bridgeHandler != null) {
				channel.unsubscribe(bridgeHandler);
				((DefaultSingletonBeanRegistry) getApplicationContext().getBeanFactory())
						.destroySingleton(errorBridgeHandlerName);
			}
			((DefaultSingletonBeanRegistry) getApplicationContext().getBeanFactory())
					.destroySingleton(errorChannelName);
		}
	}

	private void destroyErrorInfrastructure(ConsumerDestination destination, String group, C properties) {
		try {
			String recoverer = getErrorRecovererName(destination, group, properties);
			if (getApplicationContext().containsBean(recoverer)) {
				((DefaultSingletonBeanRegistry) getApplicationContext().getBeanFactory()).destroySingleton(recoverer);
			}
			String errorChannelName = errorsBaseName(destination, group, properties);
			String errorMessageHandlerName = getErrorMessageHandlerName(destination, group, properties);
			String errorBridgeHandlerName = getErrorBridgeName(destination, group, properties);
			MessageHandler bridgeHandler = null;
			if (getApplicationContext().containsBean(errorBridgeHandlerName)) {
				bridgeHandler = getApplicationContext().getBean(errorBridgeHandlerName, MessageHandler.class);
			}
			MessageHandler handler = null;
			if (getApplicationContext().containsBean(errorMessageHandlerName)) {
				handler = getApplicationContext().getBean(errorMessageHandlerName, MessageHandler.class);
			}
			if (getApplicationContext().containsBean(errorChannelName)) {
				SubscribableChannel channel = getApplicationContext().getBean(errorChannelName, SubscribableChannel.class);
				if (bridgeHandler != null) {
					channel.unsubscribe(bridgeHandler);
					((DefaultSingletonBeanRegistry) getApplicationContext().getBeanFactory())
							.destroySingleton(errorBridgeHandlerName);
				}
				if (handler != null) {
					channel.unsubscribe(handler);
					((DefaultSingletonBeanRegistry) getApplicationContext().getBeanFactory())
							.destroySingleton(errorMessageHandlerName);
				}
				((DefaultSingletonBeanRegistry) getApplicationContext().getBeanFactory())
						.destroySingleton(errorChannelName);
			}
		}
		catch (IllegalStateException e) {
			// context is shutting down.
		}
	}

	/**
	 * Binders can return a message handler to be subscribed to the error channel.
	 * Examples might be if the user wishes to (re)publish messages to a DLQ.
	 * @param destination the destination.
	 * @param group the group.
	 * @param consumerProperties the properties.
	 * @return the handler (may be null, which is the default, causing the exception to be
	 * rethrown).
	 */
	protected MessageHandler getErrorMessageHandler(final ConsumerDestination destination, final String group,
			final C consumerProperties) {
		return null;
	}

	/**
	 * Return the default error message handler, which throws the error message payload to
	 * the caller if there are no user handlers subscribed. The handler is ordered so it
	 * runs after any user-defined handlers that are subscribed.
	 * @param errorChannel the error channel.
	 * @param defaultErrorChannelPresent true if the context has a default 'errorChannel'.
	 * @return the handler.
	 */
	protected MessageHandler getDefaultErrorMessageHandler(LastSubscriberAwareChannel errorChannel,
			boolean defaultErrorChannelPresent) {
		return new FinalRethrowingErrorMessageHandler(errorChannel, defaultErrorChannelPresent);
	}

	/**
	 * Binders can return an {@link ErrorMessageStrategy} for building error messages; binder
	 * implementations typically might add extra headers to the error message.
	 * @return the implementation - may be null.
	 */
	protected ErrorMessageStrategy getErrorMessageStrategy() {
		return null;
	}

	protected String getErrorRecovererName(ConsumerDestination destination, String group,
			C consumerProperties) {
		return errorsBaseName(destination, group, consumerProperties) + ".recoverer";
	}

	protected String getErrorMessageHandlerName(ConsumerDestination destination, String group,
			C consumerProperties) {
		return errorsBaseName(destination, group, consumerProperties) + ".handler";
	}

	protected String getErrorBridgeName(ConsumerDestination destination, String group,
			C consumerProperties) {
		return errorsBaseName(destination, group, consumerProperties) + ".bridge";
	}

	protected String errorsBaseName(ConsumerDestination destination, String group,
			C consumerProperties) {
		return destination.getName() + "." + group + ".errors";
	}

	protected String getErrorBridgeName(ProducerDestination destination) {
		return errorsBaseName(destination) + ".bridge";
	}

	protected String errorsBaseName(ProducerDestination destination) {
		return destination.getName() + ".errors";
	}

	@Deprecated
	private final class ReceivingHandler extends AbstractReplyProducingMessageHandler {

		private final boolean extractEmbeddedHeaders;

		private ReceivingHandler(boolean extractEmbeddedHeaders) {
			this.extractEmbeddedHeaders = extractEmbeddedHeaders;
		}

		@Override
		@SuppressWarnings("unchecked")
		protected Object handleRequestMessage(Message<?> requestMessage) {
			if (!(requestMessage.getPayload() instanceof byte[])
					&& !requestMessage.getHeaders().containsKey(BinderHeaders.BINDER_ORIGINAL_CONTENT_TYPE)) {
				return requestMessage;
			}
			MessageValues messageValues;
			if (this.extractEmbeddedHeaders) {
				try {
					messageValues = EmbeddedHeaderUtils.extractHeaders((Message<byte[]>) requestMessage,
							true);
				}
				catch (Exception e) {
					AbstractMessageChannelBinder.this.logger.error(
							EmbeddedHeaderUtils.decodeExceptionMessage(
									requestMessage),
							e);
					messageValues = new MessageValues(requestMessage);
				}
				messageValues = deserializePayloadIfNecessary(messageValues);
			}
			else {
				MimeType contentType = AbstractMessageChannelBinder.this.contentTypeResolver.resolve(requestMessage.getHeaders());
				if (contentType != null && !MimeTypeUtils.APPLICATION_OCTET_STREAM.equals(contentType)) {
					messageValues = deserializePayloadIfNecessary(requestMessage);
				}
				else {
					return requestMessage;
				}
			}
			return messageValues.toMessage();
		}

		@Override
		protected boolean shouldCopyRequestHeaders() {
			// prevent the message from being copied again in superclass
			return false;
		}
	}

	@Deprecated
	private final class SendingHandler extends AbstractMessageHandler implements Lifecycle {

		private final boolean embedHeaders;

		private final String[] embeddedHeaders;

		private final MessageHandler delegate;


		private SendingHandler(MessageHandler delegate, boolean embedHeaders,
				String[] headersToEmbed, boolean useNativeEncoding) {
			this.delegate = delegate;
			this.setBeanFactory(AbstractMessageChannelBinder.this.getBeanFactory());
			this.embedHeaders = embedHeaders;
			this.embeddedHeaders = headersToEmbed;
			this.useNativeEncoding = useNativeEncoding;
		}

		private final boolean useNativeEncoding;

		@Override
		protected void handleMessageInternal(Message<?> message) throws Exception {
			Message<?> messageToSend = (this.useNativeEncoding) ? message
					: serializeAndEmbedHeadersIfApplicable(message);
			this.delegate.handleMessage(messageToSend);
		}


		private Message<?> serializeAndEmbedHeadersIfApplicable(Message<?> message) throws Exception {
			MessageValues transformed = serializePayloadIfNecessary(message);
			byte[] payload;
			if (this.embedHeaders) {
				Object contentType = transformed.get(MessageHeaders.CONTENT_TYPE);
				// transform content type headers to String, so that they can be properly
				// embedded in JSON
				if (contentType instanceof MimeType) {
					transformed.put(MessageHeaders.CONTENT_TYPE, contentType.toString());
				}
				Object originalContentType = transformed.get(BinderHeaders.BINDER_ORIGINAL_CONTENT_TYPE);
				if (originalContentType instanceof MimeType) {
					transformed.put(BinderHeaders.BINDER_ORIGINAL_CONTENT_TYPE, originalContentType.toString());
				}
				payload = EmbeddedHeaderUtils.embedHeaders(transformed, this.embeddedHeaders);
			}
			else {
				payload = (byte[]) transformed.getPayload();
			}
			return getMessageBuilderFactory().withPayload(payload).copyHeaders(transformed.getHeaders()).build();
		}

		@Override
		public void start() {
			if (this.delegate instanceof Lifecycle) {
				((Lifecycle) this.delegate).start();
			}
		}

		@Override
		public void stop() {
			if (this.delegate instanceof Lifecycle) {
				((Lifecycle) this.delegate).stop();
			}
		}

		@Override
		public boolean isRunning() {
			return this.delegate instanceof Lifecycle && ((Lifecycle) this.delegate).isRunning();
		}
	}

	protected static class ErrorInfrastructure {

		private final SubscribableChannel errorChannel;

		private final ErrorMessageSendingRecoverer recoverer;

		private final MessageHandler handler;

		ErrorInfrastructure(SubscribableChannel errorChannel, ErrorMessageSendingRecoverer recoverer,
				MessageHandler handler) {
			this.errorChannel = errorChannel;
			this.recoverer = recoverer;
			this.handler = handler;
		}

		public SubscribableChannel getErrorChannel() {
			return this.errorChannel;
		}

		public ErrorMessageSendingRecoverer getRecoverer() {
			return this.recoverer;
		}

		public MessageHandler getHandler() {
			return this.handler;
		}

	}

}
