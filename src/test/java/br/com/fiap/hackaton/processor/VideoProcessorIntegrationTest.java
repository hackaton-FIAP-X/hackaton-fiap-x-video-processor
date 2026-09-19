package br.com.fiap.hackaton.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.fiap.hackaton.processor.infrastructure.messaging.RabbitTopologyConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Fluxo real ponta a ponta com RabbitMQ e MinIO em containers e o ffmpeg do PATH: evento {@code
 * video.uploaded} entra, ZIP com frames sai no storage e {@code video.processed} chega na fila de
 * status do video-service. Cobre tambem o video corrompido (FAILED + DLQ, criterio da WRK-6) e a
 * reentrega (WRK-7).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class VideoProcessorIntegrationTest {

  private static final String BUCKET = "fiapx";

  @Container @ServiceConnection
  static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @Container
  static MinIOContainer minio =
      new MinIOContainer(
          // o MinIO saiu do Docker Hub; mesma imagem publicada no quay.io
          DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-10-13T13-34-11Z")
              .asCompatibleSubstituteFor("minio/minio"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("storage.endpoint", minio::getS3URL);
    registry.add("storage.access-key", minio::getUserName);
    registry.add("storage.secret-key", minio::getPassword);
    registry.add("storage.bucket", () -> BUCKET);
    registry.add("processor.backoff.initial", () -> "100ms");
  }

  @BeforeAll
  static void requiresFfmpeg() {
    assumeTrue(ffmpegAvailable(), "ffmpeg nao esta no PATH");
  }

  @Autowired RabbitTemplate rabbitTemplate;
  @Autowired S3Client s3;
  @Autowired ObjectMapper mapper;

  private void ensureBucket() {
    if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
      s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }
  }

  private String putVideo(UUID userId, UUID videoId, byte[] content) {
    ensureBucket();
    String key = "fiapx/inputs/%s/%s/aula.mp4".formatted(userId, videoId);
    s3.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromBytes(content));
    return key;
  }

  private void publishUploaded(UUID userId, UUID videoId, String key) {
    String json =
        """
        {"videoId":"%s","userId":"%s","storageKey":"%s","originalFilename":"aula.mp4",
         "fps":1,"requestedAt":"2026-09-16T23:46:34.123","traceId":"it-%s"}
        """
            .formatted(videoId, userId, key, videoId);
    Message message =
        MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    rabbitTemplate.send(
        RabbitTopologyConfig.VIDEO_EXCHANGE, RabbitTopologyConfig.UPLOADED_ROUTING_KEY, message);
  }

  /** Le a fila de status (a mesma que o video-service consome) ate achar o evento do video. */
  private Message awaitStatusEvent(UUID videoId) throws IOException {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      Message m = rabbitTemplate.receive(RabbitTopologyConfig.STATUS_QUEUE, 1000);
      if (m != null
          && mapper.readTree(m.getBody()).get("videoId").asText().equals(videoId.toString())) {
        return m;
      }
    }
    throw new AssertionError("nenhum evento de status para " + videoId);
  }

  @Test
  void videoValidoViraZipComFramesEVideoProcessed() throws Exception {
    UUID user = UUID.randomUUID();
    UUID video = UUID.randomUUID();
    byte[] mp4 = getClass().getResourceAsStream("/fixtures/sample-3s.mp4").readAllBytes();
    publishUploaded(user, video, putVideo(user, video, mp4));

    Message status = awaitStatusEvent(video);

    assertThat(status.getMessageProperties().getReceivedRoutingKey()).isEqualTo("video.processed");
    JsonNode event = mapper.readTree(status.getBody());
    String zipKey = "fiapx/outputs/%s/%s.zip".formatted(user, video);
    assertThat(event.get("zipKey").asText()).isEqualTo(zipKey);
    assertThat(event.get("userId").asText()).isEqualTo(user.toString());
    assertThat(event.get("traceId").asText()).isEqualTo("it-" + video);
    int frameCount = event.get("frameCount").asInt();
    assertThat(frameCount).isBetween(3, 4);
    assertThat(zipEntries(zipKey)).hasSize(frameCount).allMatch(n -> n.endsWith(".jpg"));

    // WRK-7: reentrega da mesma mensagem republica o sucesso com o mesmo frameCount
    publishUploaded(user, video, "fiapx/inputs/%s/%s/aula.mp4".formatted(user, video));
    JsonNode again = mapper.readTree(awaitStatusEvent(video).getBody());
    assertThat(again.get("frameCount").asInt()).isEqualTo(frameCount);
  }

  @Test
  void videoCorrompidoViraVideoFailedEVaiParaADlq() throws Exception {
    UUID user = UUID.randomUUID();
    UUID video = UUID.randomUUID();
    publishUploaded(user, video, putVideo(user, video, "nao sou um mp4".getBytes()));

    Message status = awaitStatusEvent(video);

    assertThat(status.getMessageProperties().getReceivedRoutingKey()).isEqualTo("video.failed");
    JsonNode event = mapper.readTree(status.getBody());
    assertThat(event.get("errorCode").asText()).isEqualTo("INVALID_VIDEO");
    assertThat(event.get("attempts").asInt()).isEqualTo(1);

    // outros testes tambem mandam mensagens para a DLQ: procura a deste video
    boolean deadLettered = false;
    for (int i = 0; i < 20 && !deadLettered; i++) {
      Message dead = rabbitTemplate.receive(RabbitTopologyConfig.PROCESSING_DLQ, 500);
      deadLettered =
          dead != null
              && new String(dead.getBody(), StandardCharsets.UTF_8).contains(video.toString());
    }
    assertThat(deadLettered).as("mensagem do video corrompido na DLQ").isTrue();
  }

  @Test
  void videoInexistenteNoStorageViraInputNotFound() throws Exception {
    ensureBucket();
    UUID user = UUID.randomUUID();
    UUID video = UUID.randomUUID();
    publishUploaded(user, video, "fiapx/inputs/%s/%s/sumiu.mp4".formatted(user, video));

    JsonNode event = mapper.readTree(awaitStatusEvent(video).getBody());

    assertThat(event.get("errorCode").asText()).isEqualTo("INPUT_NOT_FOUND");
  }

  private List<String> zipEntries(String key) throws IOException {
    byte[] zip =
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
            .asByteArray();
    List<String> names = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        names.add(e.getName());
      }
    }
    return names;
  }

  static boolean ffmpegAvailable() {
    try {
      return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
