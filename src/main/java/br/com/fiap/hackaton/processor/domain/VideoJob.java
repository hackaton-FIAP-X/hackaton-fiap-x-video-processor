package br.com.fiap.hackaton.processor.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de processamento de um video, vindo do evento {@code video.uploaded}.
 *
 * @param storageKey chave do video original no bucket (ex.: {@code
 *     fiapx/inputs/{userId}/{videoId}/aula.mp4})
 * @param fps quantos frames extrair por segundo de video
 */
public record VideoJob(
    UUID videoId,
    UUID userId,
    String storageKey,
    String originalFilename,
    int fps,
    String traceId) {

  public VideoJob {
    Objects.requireNonNull(videoId, "videoId");
    Objects.requireNonNull(userId, "userId");
    if (storageKey == null || storageKey.isBlank()) {
      throw new IllegalArgumentException("storageKey vazio");
    }
    if (fps < 1) {
      throw new IllegalArgumentException("fps deve ser >= 1");
    }
  }

  /** Chave do ZIP de frames, no mesmo layout do video-service ({@code StorageKey.forOutput}). */
  public String zipKey() {
    return "fiapx/outputs/%s/%s.zip".formatted(userId, videoId);
  }
}
