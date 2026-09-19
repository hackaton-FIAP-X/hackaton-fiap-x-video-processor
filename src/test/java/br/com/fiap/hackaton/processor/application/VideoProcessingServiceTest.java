package br.com.fiap.hackaton.processor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.fiap.hackaton.processor.TestJobs;
import br.com.fiap.hackaton.processor.application.port.FrameExtractor;
import br.com.fiap.hackaton.processor.application.port.VideoStorage;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import br.com.fiap.hackaton.processor.domain.ProcessingResult;
import br.com.fiap.hackaton.processor.domain.VideoJob;
import br.com.fiap.hackaton.processor.infrastructure.config.TestProperties;
import br.com.fiap.hackaton.processor.infrastructure.zip.ZipPackager;

class VideoProcessingServiceTest {

  @TempDir Path workDir;

  private final VideoStorage storage = mock(VideoStorage.class);
  private final FrameExtractor extractor = mock(FrameExtractor.class);
  private VideoProcessingService service;
  private final VideoJob job = TestJobs.job();

  @BeforeEach
  void setUp() {
    service =
        new VideoProcessingService(
            storage, extractor, new ZipPackager(), TestProperties.defaults(workDir));
    when(storage.findZipFrameCount(any())).thenReturn(OptionalInt.empty());
    doAnswer(
            inv -> {
              Files.writeString(inv.getArgument(1), "video");
              return null;
            })
        .when(storage)
        .download(any(), any());
  }

  private void extractorWrites(int frames) {
    when(extractor.extract(any(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              Path dir = inv.getArgument(2);
              for (int i = 1; i <= frames; i++) {
                Files.writeString(dir.resolve("frame_%05d.jpg".formatted(i)), "f" + i);
              }
              return frames;
            });
  }

  @Test
  void baixaExtraiEmpacotaESobeOZipNaChaveDoVideo() {
    extractorWrites(3);

    ProcessingResult result = service.process(job);

    assertThat(result)
        .isEqualTo(
            new ProcessingResult(
                "fiapx/outputs/%s/%s.zip".formatted(job.userId(), job.videoId()), 3, false));
    verify(storage).download(eq(job.storageKey()), any());
    verify(storage).uploadZip(eq(result.zipKey()), any(), eq(3));
  }

  @Test
  void zipJaExistenteEReaproveitadoSemBaixarNemRodarFfmpeg() {
    when(storage.findZipFrameCount(job.zipKey())).thenReturn(OptionalInt.of(7));

    ProcessingResult result = service.process(job);

    assertThat(result).isEqualTo(new ProcessingResult(job.zipKey(), 7, true));
    verify(storage, never()).download(any(), any());
    verify(extractor, never()).extract(any(), anyInt(), any());
    verify(storage, never()).uploadZip(any(), any(), anyInt());
  }

  @Test
  void videoSemFramesFalhaComNoFrames() {
    when(extractor.extract(any(), anyInt(), any())).thenReturn(0);

    assertThatThrownBy(() -> service.process(job))
        .isInstanceOf(ProcessingException.class)
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.NO_FRAMES);
    verify(storage, never()).uploadZip(any(), any(), anyInt());
  }

  @Test
  void diretorioDeTrabalhoELimpoNoSucessoENaFalha() throws IOException {
    extractorWrites(2);
    service.process(job);
    assertThat(listing()).isEmpty();

    doThrow(new ProcessingException(ErrorCode.STORAGE_ERROR, "s3 fora"))
        .when(storage)
        .uploadZip(any(), any(), anyInt());
    assertThatThrownBy(() -> service.process(job)).isInstanceOf(ProcessingException.class);
    assertThat(listing()).isEmpty();
  }

  @Test
  void nomeOriginalSoEmprestaAExtensaoENuncaViraCaminho() {
    extractorWrites(1);
    VideoJob malicioso =
        new VideoJob(job.videoId(), job.userId(), job.storageKey(), "../../etc/passwd.MP4", 1, "t");

    service.process(malicioso);

    ArgumentCaptor<Path> target = ArgumentCaptor.forClass(Path.class);
    verify(storage).download(any(), target.capture());
    assertThat(target.getValue().getFileName().toString()).isEqualTo("input.mp4");
    assertThat(target.getValue().getParent().getParent()).isEqualTo(workDir);
  }

  @Test
  void extensaoSegura() {
    assertThat(VideoProcessingService.safeExtension("aula.MKV")).isEqualTo(".mkv");
    assertThat(VideoProcessingService.safeExtension("sem-extensao")).isEmpty();
    assertThat(VideoProcessingService.safeExtension("ponto-no-fim.")).isEmpty();
    assertThat(VideoProcessingService.safeExtension("x.m p4")).isEmpty();
    assertThat(VideoProcessingService.safeExtension(null)).isEmpty();
  }

  @Test
  void fpsELimitadoAoMaximoConfigurado() {
    extractorWrites(1);
    VideoJob rapido =
        new VideoJob(job.videoId(), job.userId(), job.storageKey(), "a.mp4", 500, "t");

    service.process(rapido);

    verify(extractor).extract(any(), eq(30), any());
  }

  @Test
  void falhaAoCriarDiretorioDeTrabalhoViraWorkdirError() throws IOException {
    Path arquivo = Files.writeString(workDir.resolve("nao-e-diretorio"), "x");
    var comWorkDirInvalido =
        new VideoProcessingService(
            storage, extractor, new ZipPackager(), TestProperties.defaults(arquivo));

    assertThatThrownBy(() -> comWorkDirInvalido.process(job))
        .isInstanceOf(ProcessingException.class)
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.WORKDIR_ERROR);
  }

  @Test
  void limpezaToleraDiretorioInexistente() {
    VideoProcessingService.deleteRecursively(workDir.resolve("nao-existe"));
    VideoProcessingService.deleteRecursively(null);
  }

  private java.util.List<Path> listing() throws IOException {
    try (Stream<Path> s = Files.list(workDir)) {
      return s.toList();
    }
  }
}
