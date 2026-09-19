package br.com.fiap.hackaton.processor.infrastructure.messaging;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payloads trocados com o video-service. Nomes de campo iguais aos records de la. */
final class VideoEvents {

  private VideoEvents() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record VideoUploaded(
      UUID videoId,
      UUID userId,
      String storageKey,
      String originalFilename,
      Integer fps,
      LocalDateTime requestedAt,
      String traceId) {}

  record VideoProcessed(
      UUID videoId,
      UUID userId,
      String zipKey,
      Integer frameCount,
      LocalDateTime finishedAt,
      String traceId) {}

  record VideoFailed(
      UUID videoId,
      UUID userId,
      String errorCode,
      String errorMessage,
      Integer attempts,
      String traceId) {}
}
