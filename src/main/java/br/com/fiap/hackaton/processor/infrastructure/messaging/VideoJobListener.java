package br.com.fiap.hackaton.processor.infrastructure.messaging;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import br.com.fiap.hackaton.processor.application.VideoProcessingService;
import br.com.fiap.hackaton.processor.application.port.ResultPublisher;
import br.com.fiap.hackaton.processor.application.port.ResultPublisher.PublishException;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import br.com.fiap.hackaton.processor.domain.ProcessingResult;
import br.com.fiap.hackaton.processor.domain.VideoJob;
import br.com.fiap.hackaton.processor.infrastructure.config.ProcessorProperties;
import br.com.fiap.hackaton.processor.infrastructure.metrics.ProcessingMetrics;

/**
 * Consome {@code video.processing} (WRK-1) e decide o destino de cada mensagem.
 *
 * <ul>
 *   <li>sucesso: publica {@code video.processed} e so entao da ack;
 *   <li>falha transitoria: tenta de novo com backoff exponencial, ate {@code max-attempts} (WRK-6);
 *   <li>falha definitiva ou tentativas esgotadas: publica {@code video.failed} e rejeita sem
 *       requeue, o que manda a mensagem para a DLQ {@code video.processing.dlq};
 *   <li>broker indisponivel na hora de publicar o resultado: devolve a mensagem para a fila. Na
 *       reentrega o ZIP ja existe e o resultado e republicado sem reprocessar (WRK-7).
 * </ul>
 */
@Component
public class VideoJobListener {

  private static final Logger log = LoggerFactory.getLogger(VideoJobListener.class);

  private final VideoProcessingService processingService;
  private final ResultPublisher publisher;
  private final ProcessingMetrics metrics;
  private final ObjectMapper objectMapper;
  private final ProcessorProperties properties;
  private final Sleeper sleeper;

  public VideoJobListener(
      VideoProcessingService processingService,
      ResultPublisher publisher,
      ProcessingMetrics metrics,
      ObjectMapper objectMapper,
      ProcessorProperties properties,
      Sleeper sleeper) {
    this.processingService = processingService;
    this.publisher = publisher;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.sleeper = sleeper;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.PROCESSING_QUEUE,
      containerFactory = "processingContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();

    VideoJob job;
    try {
      job = parse(message);
    } catch (IllegalArgumentException e) {
      log.error("Mensagem invalida enviada para a DLQ: {}", e.getMessage());
      metrics.recordFailure(ErrorCode.INVALID_MESSAGE);
      channel.basicReject(tag, false);
      return;
    }

    int attempt = 0;
    while (true) {
      attempt++;
      long start = System.nanoTime();
      try {
        ProcessingResult result = processingService.process(job);
        publisher.publishProcessed(job, result);
        metrics.recordSuccess(Duration.ofNanos(System.nanoTime() - start), result.reused());
        channel.basicAck(tag, false);
        return;
      } catch (PublishException e) {
        requeue(channel, tag, job, e);
        return;
      } catch (ProcessingException e) {
        if (e.code().retryable() && attempt < properties.maxAttempts()) {
          if (!backoff(job, attempt, e)) {
            requeue(channel, tag, job, e);
            return;
          }
          continue;
        }
        fail(channel, tag, job, e.code(), e.getMessage(), attempt);
        return;
      } catch (RuntimeException e) {
        ProcessingException wrapped =
            new ProcessingException(ErrorCode.UNEXPECTED, ErrorCode.UNEXPECTED.description(), e);
        if (attempt < properties.maxAttempts()) {
          log.warn("Erro inesperado no video {}, tentativa {}", job.videoId(), attempt, e);
          if (!backoff(job, attempt, wrapped)) {
            requeue(channel, tag, job, wrapped);
            return;
          }
          continue;
        }
        fail(channel, tag, job, ErrorCode.UNEXPECTED, describe(e), attempt);
        return;
      }
    }
  }

  private boolean backoff(VideoJob job, int attempt, ProcessingException cause) {
    Duration delay = properties.backoff().delayAfter(attempt);
    log.warn(
        "Video {} falhou na tentativa {}/{} ({}); nova tentativa em {} ms [trace={}]",
        job.videoId(),
        attempt,
        properties.maxAttempts(),
        cause.code(),
        delay.toMillis(),
        job.traceId());
    try {
      sleeper.sleep(delay);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void fail(
      Channel channel, long tag, VideoJob job, ErrorCode code, String detail, int attempts)
      throws IOException {
    String message = detail == null || detail.isBlank() ? code.description() : detail;
    try {
      publisher.publishFailed(job, code, message, attempts);
    } catch (PublishException e) {
      requeue(channel, tag, job, e);
      return;
    }
    metrics.recordFailure(code);
    log.error(
        "Video {} falhou definitivamente apos {} tentativa(s): [{}] {} [trace={}]",
        job.videoId(),
        attempts,
        code,
        message,
        job.traceId());
    channel.basicReject(tag, false);
  }

  private void requeue(Channel channel, long tag, VideoJob job, Exception cause)
      throws IOException {
    log.warn(
        "Resultado do video {} nao publicado; mensagem devolvida para a fila [trace={}]",
        job.videoId(),
        job.traceId(),
        cause);
    channel.basicNack(tag, false, true);
  }

  private VideoJob parse(Message message) {
    VideoEvents.VideoUploaded event;
    try {
      event = objectMapper.readValue(message.getBody(), VideoEvents.VideoUploaded.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("JSON invalido: " + e.getMessage(), e);
    }
    int fps = event.fps() == null || event.fps() < 1 ? 1 : event.fps();
    String traceId = event.traceId() != null ? event.traceId() : UUID.randomUUID().toString();
    try {
      return new VideoJob(
          event.videoId(),
          event.userId(),
          event.storageKey(),
          event.originalFilename(),
          fps,
          traceId);
    } catch (NullPointerException e) {
      throw new IllegalArgumentException("campo obrigatorio ausente: " + e.getMessage(), e);
    }
  }

  private static String describe(Throwable e) {
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }
}
