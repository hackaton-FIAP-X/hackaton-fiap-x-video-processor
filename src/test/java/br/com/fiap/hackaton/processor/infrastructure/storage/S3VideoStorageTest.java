package br.com.fiap.hackaton.processor.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3VideoStorageTest {

  @TempDir Path tmp;

  private final S3Client s3 = mock(S3Client.class);
  private final S3VideoStorage storage =
      new S3VideoStorage(s3, new StorageProperties(null, null, null, "fiapx", "us-east-1"));

  private static HeadObjectResponse head(Map<String, String> metadata) {
    return HeadObjectResponse.builder().metadata(metadata).build();
  }

  @Test
  void frameCountVemDaMetadataDoZip() {
    when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head(Map.of("frame-count", "12")));

    assertThat(storage.findZipFrameCount("k").getAsInt()).isEqualTo(12);
  }

  @Test
  void zipInexistenteOuSemMetadataValidaRetornaVazio() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build())
        .thenThrow(S3Exception.builder().statusCode(404).build())
        .thenReturn(head(Map.of()))
        .thenReturn(head(Map.of("frame-count", "abc")));

    for (int i = 0; i < 4; i++) {
      assertThat(storage.findZipFrameCount("k")).isEmpty();
    }
  }

  @Test
  void erroDoStorageNaConsultaETransitorio() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(503).build())
        .thenThrow(SdkClientException.create("timeout"));

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> storage.findZipFrameCount("k"))
          .extracting(e -> ((ProcessingException) e).code())
          .isEqualTo(ErrorCode.STORAGE_ERROR);
    }
  }

  @Test
  void downloadDeObjetoInexistenteEDefinitivoEOutroErroETransitorio() {
    when(s3.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(NoSuchKeyException.builder().build())
        .thenThrow(SdkClientException.create("reset"));

    assertThatThrownBy(() -> storage.download("k", tmp.resolve("a")))
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.INPUT_NOT_FOUND);
    assertThatThrownBy(() -> storage.download("k", tmp.resolve("b")))
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.STORAGE_ERROR);
  }

  @Test
  void uploadGravaContentTypeEFrameCountNoBucketConfigurado() throws Exception {
    Path zip = Files.writeString(tmp.resolve("f.zip"), "zip");

    storage.uploadZip("fiapx/outputs/u/v.zip", zip, 5);

    ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3).putObject(req.capture(), any(RequestBody.class));
    assertThat(req.getValue().bucket()).isEqualTo("fiapx");
    assertThat(req.getValue().key()).isEqualTo("fiapx/outputs/u/v.zip");
    assertThat(req.getValue().contentType()).isEqualTo("application/zip");
    assertThat(req.getValue().metadata()).containsEntry("frame-count", "5");
  }

  @Test
  void falhaNoUploadETransitoria() throws Exception {
    Path zip = Files.writeString(tmp.resolve("f.zip"), "zip");
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(SdkClientException.create("reset"));

    assertThatThrownBy(() -> storage.uploadZip("k", zip, 1))
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.STORAGE_ERROR);
  }
}
