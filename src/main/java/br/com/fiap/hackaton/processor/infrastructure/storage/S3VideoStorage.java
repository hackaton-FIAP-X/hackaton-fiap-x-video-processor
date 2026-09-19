package br.com.fiap.hackaton.processor.infrastructure.storage;

import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import br.com.fiap.hackaton.processor.application.port.VideoStorage;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Adaptador S3 (MinIO no local, S3 na AWS) da porta {@link VideoStorage}. */
@Component
public class S3VideoStorage implements VideoStorage {

  /** Metadata do ZIP com a quantidade de frames, usada na reentrega (WRK-7). */
  static final String FRAME_COUNT_METADATA = "frame-count";

  private final S3Client s3;
  private final String bucket;

  public S3VideoStorage(S3Client s3, StorageProperties properties) {
    this.s3 = s3;
    this.bucket = properties.bucket();
  }

  @Override
  public void download(String key, Path target) {
    try {
      s3.getObject(
          GetObjectRequest.builder().bucket(bucket).key(key).build(),
          ResponseTransformer.toFile(target));
    } catch (NoSuchKeyException e) {
      throw new ProcessingException(
          ErrorCode.INPUT_NOT_FOUND, "Video original nao existe em " + key, e);
    } catch (SdkException e) {
      throw new ProcessingException(ErrorCode.STORAGE_ERROR, "Falha ao baixar " + key, e);
    }
  }

  @Override
  public void uploadZip(String key, Path zipFile, int frameCount) {
    try {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("application/zip")
              .metadata(Map.of(FRAME_COUNT_METADATA, Integer.toString(frameCount)))
              .build(),
          RequestBody.fromFile(zipFile));
    } catch (SdkException e) {
      throw new ProcessingException(ErrorCode.STORAGE_ERROR, "Falha ao enviar " + key, e);
    }
  }

  @Override
  public OptionalInt findZipFrameCount(String key) {
    HeadObjectResponse head;
    try {
      head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (NoSuchKeyException e) {
      return OptionalInt.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return OptionalInt.empty();
      }
      throw new ProcessingException(ErrorCode.STORAGE_ERROR, "Falha ao consultar " + key, e);
    } catch (SdkException e) {
      throw new ProcessingException(ErrorCode.STORAGE_ERROR, "Falha ao consultar " + key, e);
    }
    String count = head.metadata().get(FRAME_COUNT_METADATA);
    try {
      return count == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(count));
    } catch (NumberFormatException e) {
      // ZIP sem metadata valida: reprocessa em vez de publicar um frameCount inventado
      return OptionalInt.empty();
    }
  }
}
