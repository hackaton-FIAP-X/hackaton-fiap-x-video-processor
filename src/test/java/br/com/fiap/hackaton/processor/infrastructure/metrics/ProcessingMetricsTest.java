package br.com.fiap.hackaton.processor.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import br.com.fiap.hackaton.processor.domain.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ProcessingMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

  @Test
  void sucessoContaEMedeADuracaoMasReentregaNaoEntraNaDuracao() {
    metrics.recordSuccess(Duration.ofSeconds(2), false);
    metrics.recordSuccess(Duration.ofMillis(5), true);

    assertThat(registry.counter("videos.processed").count()).isEqualTo(2);
    assertThat(registry.counter("videos.reused").count()).isEqualTo(1);
    assertThat(registry.timer("video.processing").count()).isEqualTo(1);
  }

  @Test
  void falhaEContadaPorCodigoDeErro() {
    metrics.recordFailure(ErrorCode.INVALID_VIDEO);
    metrics.recordFailure(ErrorCode.INVALID_VIDEO);
    metrics.recordFailure(ErrorCode.NO_FRAMES);

    assertThat(registry.counter("videos.failed", "error_code", "INVALID_VIDEO").count())
        .isEqualTo(2);
    assertThat(registry.counter("videos.failed", "error_code", "NO_FRAMES").count()).isEqualTo(1);
  }
}
