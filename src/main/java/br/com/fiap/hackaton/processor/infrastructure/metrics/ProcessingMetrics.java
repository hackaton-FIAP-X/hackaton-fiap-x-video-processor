package br.com.fiap.hackaton.processor.infrastructure.metrics;

import java.time.Duration;

import org.springframework.stereotype.Component;

import br.com.fiap.hackaton.processor.domain.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Metricas do worker (WRK-8). Os nomes sao os que o dashboard do Grafana (repo infra) consulta:
 * {@code videos_processed_total}, {@code video_processing_seconds_bucket} e {@code
 * videos_failed_total{error_code}}.
 */
@Component
public class ProcessingMetrics {

  private final MeterRegistry registry;
  private final Counter processed;
  private final Counter reused;
  private final Timer duration;

  public ProcessingMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.processed =
        Counter.builder("videos.processed")
            .description("Videos processados com sucesso")
            .register(registry);
    this.reused =
        Counter.builder("videos.reused")
            .description("Reentregas atendidas com um ZIP ja existente, sem rodar o FFmpeg")
            .register(registry);
    this.duration =
        Timer.builder("video.processing")
            .description("Duracao do processamento de um video")
            .publishPercentileHistogram()
            .register(registry);
  }

  public void recordSuccess(Duration elapsed, boolean reusedZip) {
    processed.increment();
    if (reusedZip) {
      reused.increment();
    } else {
      duration.record(elapsed);
    }
  }

  public void recordFailure(ErrorCode code) {
    Counter.builder("videos.failed")
        .description("Videos que terminaram em falha, por codigo de erro")
        .tag("error_code", code.name())
        .register(registry)
        .increment();
  }
}
