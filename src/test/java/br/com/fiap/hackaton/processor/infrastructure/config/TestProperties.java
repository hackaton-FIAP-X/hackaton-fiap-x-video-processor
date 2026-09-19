package br.com.fiap.hackaton.processor.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;

public final class TestProperties {

  private TestProperties() {}

  public static ProcessorProperties with(Path workDir, String ffmpegBinary, Duration timeout) {
    return new ProcessorProperties(
        1,
        1,
        3,
        new ProcessorProperties.Backoff(Duration.ofMillis(100), 2.0, Duration.ofMillis(300)),
        new ProcessorProperties.Ffmpeg(ffmpegBinary, timeout, 30),
        workDir,
        Duration.ofSeconds(2));
  }

  public static ProcessorProperties defaults(Path workDir) {
    return with(workDir, "ffmpeg", Duration.ofMinutes(1));
  }
}
