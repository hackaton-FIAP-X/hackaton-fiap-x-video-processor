package br.com.fiap.hackaton.processor.application.port;

import java.nio.file.Path;
import java.util.OptionalInt;

/** Object storage (S3 na AWS, MinIO no ambiente local). */
public interface VideoStorage {

  /** Baixa o objeto para {@code target}, que ainda nao pode existir. */
  void download(String key, Path target);

  /** Sobe o ZIP guardando a quantidade de frames na metadata do objeto. */
  void uploadZip(String key, Path zipFile, int frameCount);

  /**
   * Quantidade de frames de um ZIP ja publicado, ou vazio se ele nao existe. Base da idempotencia
   * (WRK-7).
   */
  OptionalInt findZipFrameCount(String key);
}
