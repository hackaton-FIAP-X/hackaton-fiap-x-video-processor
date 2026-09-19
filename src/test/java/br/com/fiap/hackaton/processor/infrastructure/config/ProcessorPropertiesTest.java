package br.com.fiap.hackaton.processor.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class ProcessorPropertiesTest {

  @Test
  void backoffCresceExponencialmenteAteOTeto() {
    var backoff =
        new ProcessorProperties.Backoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(3));

    assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(backoff.delayAfter(3)).isEqualTo(Duration.ofSeconds(3));
    assertThat(backoff.delayAfter(10)).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void workDirVazioCaiNoDiretorioTemporario() {
    String tmp = System.getProperty("java.io.tmpdir");

    assertThat(TestProperties.defaults(null).workDirOrTemp()).isEqualTo(Path.of(tmp));
    assertThat(TestProperties.defaults(Path.of("")).workDirOrTemp()).isEqualTo(Path.of(tmp));
    assertThat(TestProperties.defaults(Path.of("/data")).workDirOrTemp())
        .isEqualTo(Path.of("/data"));
  }
}
