package br.com.fiap.hackaton.processor.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.fiap.hackaton.processor.application.port.ResultPublisher;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingResult;
import br.com.fiap.hackaton.processor.domain.VideoJob;
import br.com.fiap.hackaton.processor.infrastructure.config.ProcessorProperties;

/**
 * Publica {@code video.processed} e {@code video.failed} com publisher confirms (WRK-5).
 *
 * <p>So retorna quando o broker confirmou e roteou a mensagem. O listener so da ack depois disso,
 * entao nenhum resultado se perde se o broker cair no meio do caminho.
 */
@Component
public class RabbitResultPublisher implements ResultPublisher {

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final long confirmTimeoutMillis;

  public RabbitResultPublisher(
      RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, ProcessorProperties properties) {
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.confirmTimeoutMillis = properties.publishConfirmTimeout().toMillis();
  }

  @Override
  public void publishProcessed(VideoJob job, ProcessingResult result) {
    send(
        RabbitTopologyConfig.PROCESSED_ROUTING_KEY,
        new VideoEvents.VideoProcessed(
            job.videoId(),
            job.userId(),
            result.zipKey(),
            result.frameCount(),
            LocalDateTime.now(ZoneOffset.UTC),
            job.traceId()));
  }

  @Override
  public void publishFailed(VideoJob job, ErrorCode code, String message, int attempts) {
    send(
        RabbitTopologyConfig.FAILED_ROUTING_KEY,
        new VideoEvents.VideoFailed(
            job.videoId(), job.userId(), code.name(), message, attempts, job.traceId()));
  }

  private void send(String routingKey, Object payload) {
    Message message = toMessage(routingKey, payload);
    CorrelationData correlation =
        new CorrelationData(message.getMessageProperties().getMessageId());
    try {
      rabbitTemplate.send(RabbitTopologyConfig.VIDEO_EXCHANGE, routingKey, message, correlation);
      CorrelationData.Confirm confirm =
          correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
      if (!confirm.isAck()) {
        throw new PublishException(
            "Broker recusou " + routingKey + ": " + confirm.getReason(), null);
      }
      if (correlation.getReturned() != null) {
        throw new PublishException("Nenhuma fila recebeu " + routingKey, null);
      }
    } catch (AmqpException | ExecutionException | TimeoutException e) {
      throw new PublishException("Falha ao publicar " + routingKey, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublishException("Publicacao de " + routingKey + " interrompida", e);
    }
  }

  private Message toMessage(String routingKey, Object payload) {
    try {
      return MessageBuilder.withBody(objectMapper.writeValueAsBytes(payload))
          .setContentType(MessageProperties.CONTENT_TYPE_JSON)
          .setContentEncoding(StandardCharsets.UTF_8.name())
          .setMessageId(UUID.randomUUID().toString())
          .setHeader("eventType", routingKey)
          .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
          .build();
    } catch (JsonProcessingException e) {
      throw new PublishException("Falha ao serializar " + routingKey, e);
    }
  }
}
