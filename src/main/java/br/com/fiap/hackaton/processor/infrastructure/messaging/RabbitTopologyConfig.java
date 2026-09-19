package br.com.fiap.hackaton.processor.infrastructure.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import br.com.fiap.hackaton.processor.infrastructure.config.ProcessorProperties;

/**
 * Topologia RabbitMQ, identica a do video-service ({@code RabbitMqConfig}).
 *
 * <p>O worker declara tambem a fila de status: assim um {@code video.processed} publicado com o
 * video-service fora do ar fica guardado na fila em vez de ser descartado como nao roteavel.
 * Declarar a mesma fila com os mesmos argumentos e idempotente; qualquer divergencia faz o RabbitMQ
 * recusar a declaracao, entao os argumentos precisam continuar iguais aos de la.
 */
@Configuration
public class RabbitTopologyConfig {

  public static final String VIDEO_EXCHANGE = "fiapx.video";
  public static final String VIDEO_DLX = "fiapx.video.dlx";
  public static final String PROCESSING_QUEUE = "video.processing";
  public static final String PROCESSING_DLQ = "video.processing.dlq";
  public static final String STATUS_QUEUE = "video.status";
  public static final String STATUS_DLQ = "video.status.dlq";
  public static final String UPLOADED_ROUTING_KEY = "video.uploaded";
  public static final String PROCESSED_ROUTING_KEY = "video.processed";
  public static final String FAILED_ROUTING_KEY = "video.failed";

  @Bean
  TopicExchange videoExchange() {
    return new TopicExchange(VIDEO_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange videoDeadLetterExchange() {
    return new DirectExchange(VIDEO_DLX, true, false);
  }

  @Bean
  Queue processingQueue() {
    return QueueBuilder.durable(PROCESSING_QUEUE)
        .quorum()
        .deadLetterExchange(VIDEO_DLX)
        .deadLetterRoutingKey(PROCESSING_QUEUE)
        .build();
  }

  @Bean
  Queue processingDeadLetterQueue() {
    return QueueBuilder.durable(PROCESSING_DLQ).quorum().build();
  }

  @Bean
  Binding processingBinding() {
    return BindingBuilder.bind(processingQueue()).to(videoExchange()).with(UPLOADED_ROUTING_KEY);
  }

  @Bean
  Binding processingDeadLetterBinding() {
    return BindingBuilder.bind(processingDeadLetterQueue())
        .to(videoDeadLetterExchange())
        .with(PROCESSING_QUEUE);
  }

  @Bean
  Queue statusQueue() {
    return QueueBuilder.durable(STATUS_QUEUE)
        .quorum()
        .deadLetterExchange(VIDEO_DLX)
        .deadLetterRoutingKey(STATUS_QUEUE)
        .build();
  }

  @Bean
  Queue statusDeadLetterQueue() {
    return QueueBuilder.durable(STATUS_DLQ).quorum().build();
  }

  @Bean
  Binding processedBinding() {
    return BindingBuilder.bind(statusQueue()).to(videoExchange()).with(PROCESSED_ROUTING_KEY);
  }

  @Bean
  Binding failedBinding() {
    return BindingBuilder.bind(statusQueue()).to(videoExchange()).with(FAILED_ROUTING_KEY);
  }

  @Bean
  Binding statusDeadLetterBinding() {
    return BindingBuilder.bind(statusDeadLetterQueue())
        .to(videoDeadLetterExchange())
        .with(STATUS_QUEUE);
  }

  /**
   * WRK-1: ack manual (so depois do resultado publicado e confirmado) e prefetch 1. Com prefetch
   * maior, um pod reservaria varias mensagens e as replicas novas do HPA ficariam sem trabalho.
   */
  @Bean
  SimpleRabbitListenerContainerFactory processingContainerFactory(
      ConnectionFactory connectionFactory, ProcessorProperties properties) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setPrefetchCount(1);
    factory.setConcurrentConsumers(properties.concurrency());
    factory.setMaxConcurrentConsumers(
        Math.max(properties.concurrency(), properties.maxConcurrency()));
    factory.setDefaultRequeueRejected(false);
    return factory;
  }
}
