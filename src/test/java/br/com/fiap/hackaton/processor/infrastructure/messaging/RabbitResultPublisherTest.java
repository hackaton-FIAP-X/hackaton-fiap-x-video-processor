package br.com.fiap.hackaton.processor.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import br.com.fiap.hackaton.processor.TestJobs;
import br.com.fiap.hackaton.processor.application.port.ResultPublisher.PublishException;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingResult;
import br.com.fiap.hackaton.processor.domain.VideoJob;
import br.com.fiap.hackaton.processor.infrastructure.config.TestProperties;

class RabbitResultPublisherTest {

  private final RabbitTemplate template = mock(RabbitTemplate.class);
  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final VideoJob job = TestJobs.job();
  private final AtomicReference<Message> sent = new AtomicReference<>();
  private final AtomicReference<String> routingKey = new AtomicReference<>();
  private RabbitResultPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher =
        new RabbitResultPublisher(template, mapper, TestProperties.defaults(Path.of("/tmp")));
  }

  private void brokerResponds(boolean ack, boolean returned) {
    doAnswer(
            inv -> {
              routingKey.set(inv.getArgument(1));
              sent.set(inv.getArgument(2));
              CorrelationData correlation = inv.getArgument(3);
              if (returned) {
                correlation.setReturned(
                    new ReturnedMessage(inv.getArgument(2), 312, "NO_ROUTE", "x", "y"));
              }
              correlation
                  .getFuture()
                  .complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
              return null;
            })
        .when(template)
        .send(eq(RabbitTopologyConfig.VIDEO_EXCHANGE), any(), any(Message.class), any());
  }

  private JsonNode body() throws Exception {
    return mapper.readTree(sent.get().getBody());
  }

  @Test
  void processedTemOsCamposQueOVideoServiceLe() throws Exception {
    brokerResponds(true, false);

    publisher.publishProcessed(job, new ProcessingResult(job.zipKey(), 3, false));

    assertThat(routingKey.get()).isEqualTo("video.processed");
    JsonNode body = body();
    assertThat(body.get("videoId").asText()).isEqualTo(job.videoId().toString());
    assertThat(body.get("userId").asText()).isEqualTo(job.userId().toString());
    assertThat(body.get("zipKey").asText()).isEqualTo(job.zipKey());
    assertThat(body.get("frameCount").asInt()).isEqualTo(3);
    assertThat(body.get("finishedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
    assertThat(body.get("traceId").asText()).isEqualTo("trace-1");
    assertThat(sent.get().getMessageProperties().getContentType()).isEqualTo("application/json");
    assertThat(sent.get().getMessageProperties().getHeaders())
        .containsEntry("eventType", "video.processed");
  }

  @Test
  void failedTemCodigoMensagemETentativas() throws Exception {
    brokerResponds(true, false);

    publisher.publishFailed(job, ErrorCode.INVALID_VIDEO, "corrompido", 1);

    assertThat(routingKey.get()).isEqualTo("video.failed");
    JsonNode body = body();
    assertThat(body.get("errorCode").asText()).isEqualTo("INVALID_VIDEO");
    assertThat(body.get("errorMessage").asText()).isEqualTo("corrompido");
    assertThat(body.get("attempts").asInt()).isEqualTo(1);
  }

  @Test
  void nackDoBrokerFalhaAPublicacao() {
    brokerResponds(false, false);

    assertThatThrownBy(() -> publisher.publishFailed(job, ErrorCode.NO_FRAMES, "x", 1))
        .isInstanceOf(PublishException.class)
        .hasMessageContaining("recusou");
  }

  @Test
  void mensagemSemFilaDeDestinoFalhaAPublicacao() {
    brokerResponds(true, true);

    assertThatThrownBy(
            () -> publisher.publishProcessed(job, new ProcessingResult(job.zipKey(), 1, false)))
        .isInstanceOf(PublishException.class)
        .hasMessageContaining("Nenhuma fila");
  }

  @Test
  void brokerForaDoArFalhaAPublicacao() {
    doThrow(new AmqpConnectException(new RuntimeException("conn refused")))
        .when(template)
        .send(any(), any(), any(Message.class), any());

    assertThatThrownBy(() -> publisher.publishFailed(job, ErrorCode.NO_FRAMES, "x", 1))
        .isInstanceOf(PublishException.class);
  }

  @Test
  void semConfirmacaoNoPrazoFalhaAPublicacao() {
    // send nao completa o future: a confirmacao nunca chega
    assertThatThrownBy(() -> publisher.publishFailed(job, ErrorCode.NO_FRAMES, "x", 1))
        .isInstanceOf(PublishException.class);
  }
}
