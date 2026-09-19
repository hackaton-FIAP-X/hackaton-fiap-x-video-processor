package br.com.fiap.hackaton.processor.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuracao do worker ({@code processor.*} no application.yml). */
@ConfigurationProperties(prefix = "processor")
public record ProcessorProperties(
    @DefaultValue("1") int concurrency,
    @DefaultValue("1") int maxConcurrency,
    @DefaultValue("3") int maxAttempts,
    @DefaultValue Backoff backoff,
    @DefaultValue Ffmpeg ffmpeg,
    Path workDir,
    @DefaultValue("10s") Duration publishConfirmTimeout) {

  /** Backoff exponencial entre tentativas (WRK-6). */
  public record Backoff(
      @DefaultValue("1s") Duration initial,
      @DefaultValue("2.0") double multiplier,
      @DefaultValue("10s") Duration max) {

    /** Espera antes da tentativa {@code attempt + 1}, com {@code attempt} comecando em 1. */
    public Duration delayAfter(int attempt) {
      double millis = initial.toMillis() * Math.pow(multiplier, attempt - 1);
      return Duration.ofMillis((long) Math.min(millis, max.toMillis()));
    }
  }

  public record Ffmpeg(
      @DefaultValue("ffmpeg") String binary,
      @DefaultValue("10m") Duration timeout,
      @DefaultValue("30") int maxFps) {}

  public Path workDirOrTemp() {
    boolean unset = workDir == null || workDir.toString().isBlank();
    return unset ? Path.of(System.getProperty("java.io.tmpdir")) : workDir;
  }
}
