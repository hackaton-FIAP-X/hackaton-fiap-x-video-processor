package br.com.fiap.hackaton.processor.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;

import br.com.fiap.hackaton.processor.application.VideoProcessingService;
import br.com.fiap.hackaton.processor.application.port.ResultPublisher;
import br.com.fiap.hackaton.processor.application.port.ResultPublisher.PublishException;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import br.com.fiap.hackaton.processor.domain.ProcessingResult;
import br.com.fiap.hackaton.processor.domain.VideoJob;
import br.com.fiap.hackaton.processor.infrastructure.config.TestProperties;
import br.com.fiap.hackaton.processor.infrastructure.metrics.ProcessingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class VideoJobListenerTest {

  private static final long TAG = 42L;

  private final VideoProcessingService service = mock(VideoProcessingService.class);
  private final ResultPublisher publisher = mock(ResultPublisher.class);
  private final Channel channel = mock(Channel.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<Duration> sleeps = new ArrayList<>();
  private VideoJobListener listener;

  private final UUID videoId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final ProcessingResult ok = new ProcessingResult("fiapx/outputs/x.zip", 3, false);

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    listener =
        new VideoJobListener(
            service,
            publisher,
            new ProcessingMetrics(registry),
            mapper,
            TestProperties.defaults(Path.of("/tmp")),
            sleeps::add);
  }

  private Message message(String json) {
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(TAG);
    return new Message(json.getBytes(StandardCharsets.UTF_8), props);
  }

  private Message uploaded() {
    return message(
        """
        {"videoId":"%s","userId":"%s","storageKey":"fiapx/inputs/%s/%s/a.mp4",
         "originalFilename":"a.mp4","fps":1,"requestedAt":"2026-09-16T23:46:34.123",
         "traceId":"t-1","campoNovo":"ignorado"}
        """
            .formatted(videoId, userId, userId, videoId));
  }

  private double failedCount(ErrorCode code) {
    var counter = registry.find("videos.failed").tag("error_code", code.name()).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void sucessoPublicaProcessedEDaAckSoDepois() throws Exception {
    when(service.process(any())).thenReturn(ok);

    listener.onMessage(uploaded(), channel);

    var order = org.mockito.Mockito.inOrder(publisher, channel);
    order.verify(publisher).publishProcessed(any(VideoJob.class), eq(ok));
    order.verify(channel).basicAck(TAG, false);
    assertThat(registry.counter("videos.processed").count()).isEqualTo(1);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void campoAusenteNoEventoUsaDefaults() throws Exception {
    when(service.process(any())).thenReturn(ok);

    listener.onMessage(
        message(
            """
            {"videoId":"%s","userId":"%s","storageKey":"k"}
            """
                .formatted(videoId, userId)),
        channel);

    var job = org.mockito.ArgumentCaptor.forClass(VideoJob.class);
    verify(service).process(job.capture());
    assertThat(job.getValue().fps()).isEqualTo(1);
    assertThat(job.getValue().traceId()).isNotBlank();
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void falhaTransitoriaTentaDeNovoComBackoffEDepoisSucede() throws Exception {
    when(service.process(any()))
        .thenThrow(new ProcessingException(ErrorCode.STORAGE_ERROR, "s3 fora"))
        .thenReturn(ok);

    listener.onMessage(uploaded(), channel);

    verify(service, times(2)).process(any());
    assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void tentativasEsgotadasPublicamFailedERejeitamParaADlq() throws Exception {
    when(service.process(any()))
        .thenThrow(new ProcessingException(ErrorCode.STORAGE_ERROR, "s3 fora"));

    listener.onMessage(uploaded(), channel);

    verify(service, times(3)).process(any());
    assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
    verify(publisher).publishFailed(any(), eq(ErrorCode.STORAGE_ERROR), eq("s3 fora"), eq(3));
    verify(channel).basicReject(TAG, false);
    verify(channel, never()).basicAck(TAG, false);
    assertThat(failedCount(ErrorCode.STORAGE_ERROR)).isEqualTo(1);
  }

  @Test
  void videoCorrompidoFalhaNaPrimeiraTentativaSemRetry() throws Exception {
    when(service.process(any()))
        .thenThrow(new ProcessingException(ErrorCode.INVALID_VIDEO, "moov atom not found"));

    listener.onMessage(uploaded(), channel);

    verify(service, times(1)).process(any());
    assertThat(sleeps).isEmpty();
    verify(publisher)
        .publishFailed(any(), eq(ErrorCode.INVALID_VIDEO), eq("moov atom not found"), eq(1));
    verify(channel).basicReject(TAG, false);
  }

  @Test
  void erroInesperadoTambemTemRetryEDepoisViraUnexpected() throws Exception {
    when(service.process(any())).thenThrow(new IllegalStateException("bug"));

    listener.onMessage(uploaded(), channel);

    verify(service, times(3)).process(any());
    verify(publisher).publishFailed(any(), eq(ErrorCode.UNEXPECTED), eq("bug"), eq(3));
    verify(channel).basicReject(TAG, false);
  }

  @Test
  void mensagemIlegivelVaiDiretoParaADlqSemPublicarNada() throws Exception {
    listener.onMessage(message("{nao e json"), channel);
    listener.onMessage(message("{\"storageKey\":\"k\"}"), channel);

    verifyNoInteractions(service, publisher);
    verify(channel, times(2)).basicReject(TAG, false);
    assertThat(failedCount(ErrorCode.INVALID_MESSAGE)).isEqualTo(2);
  }

  @Test
  void brokerIndisponivelAoPublicarResultadoDevolveAMensagemParaAFila() throws Exception {
    when(service.process(any())).thenReturn(ok);
    doThrow(new PublishException("broker fora", null))
        .when(publisher)
        .publishProcessed(any(), any());

    listener.onMessage(uploaded(), channel);

    verify(channel).basicNack(TAG, false, true);
    verify(channel, never()).basicAck(TAG, false);
    verify(publisher, never()).publishFailed(any(), any(), anyString(), anyInt());
  }

  @Test
  void brokerIndisponivelAoPublicarFalhaTambemDevolveParaAFila() throws Exception {
    when(service.process(any()))
        .thenThrow(new ProcessingException(ErrorCode.INVALID_VIDEO, "corrompido"));
    doThrow(new PublishException("broker fora", null))
        .when(publisher)
        .publishFailed(any(), any(), anyString(), anyInt());

    listener.onMessage(uploaded(), channel);

    verify(channel).basicNack(TAG, false, true);
    verify(channel, never()).basicReject(TAG, false);
    assertThat(failedCount(ErrorCode.INVALID_VIDEO)).isZero();
  }

  @Test
  void interrupcaoDuranteOBackoffDevolveParaAFila() throws Exception {
    when(service.process(any()))
        .thenThrow(new ProcessingException(ErrorCode.STORAGE_ERROR, "s3 fora"));
    var interrompido =
        new VideoJobListener(
            service,
            publisher,
            new ProcessingMetrics(registry),
            new ObjectMapper().registerModule(new JavaTimeModule()),
            TestProperties.defaults(Path.of("/tmp")),
            d -> {
              throw new InterruptedException();
            });

    interrompido.onMessage(uploaded(), channel);

    verify(channel).basicNack(TAG, false, true);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void falhaSemMensagemUsaADescricaoDoCodigo() throws Exception {
    when(service.process(any())).thenThrow(new ProcessingException(ErrorCode.NO_FRAMES, " "));

    listener.onMessage(uploaded(), channel);

    verify(publisher)
        .publishFailed(
            any(), eq(ErrorCode.NO_FRAMES), eq(ErrorCode.NO_FRAMES.description()), eq(1));
  }
}
