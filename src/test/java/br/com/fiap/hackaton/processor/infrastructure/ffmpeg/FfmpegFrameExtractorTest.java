package br.com.fiap.hackaton.processor.infrastructure.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import br.com.fiap.hackaton.processor.infrastructure.config.TestProperties;

/**
 * A maior parte usa um script no lugar do ffmpeg para exercitar o adaptador (exit code, timeout,
 * contagem de frames) sem depender do binario. O ultimo teste roda o ffmpeg de verdade quando ele
 * esta no PATH (a CI instala).
 */
class FfmpegFrameExtractorTest {

  @TempDir Path tmp;

  private Path script(String body) throws IOException {
    Path script = tmp.resolve("fake-ffmpeg-" + System.nanoTime() + ".sh");
    Files.writeString(script, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return script;
  }

  private Path framesDir() throws IOException {
    return Files.createDirectory(tmp.resolve("frames-" + System.nanoTime()));
  }

  private FfmpegFrameExtractor extractor(Path binary, Duration timeout) {
    return new FfmpegFrameExtractor(TestProperties.with(tmp, binary.toString(), timeout));
  }

  @Test
  void contaOsFramesGeradosEPassaFpsEPadraoDeSaida() throws IOException {
    // o ultimo argumento e o padrao frame_%05d.jpg; o anterior ao -q:v e o filtro fps
    Path fake =
        script(
            """
            for a in "$@"; do last="$a"; done
            echo "$@" > "$(dirname "$last")/../args.txt"
            for i in 1 2 3; do f=$(printf "$last" "$i"); echo x > "$f"; done
            """);
    Path frames = framesDir();

    int count = extractor(fake, Duration.ofSeconds(10)).extract(tmp.resolve("in.mp4"), 2, frames);

    assertThat(count).isEqualTo(3);
    assertThat(Files.readString(frames.resolveSibling("args.txt")))
        .contains("-vf fps=2")
        .contains("-i " + tmp.resolve("in.mp4"));
  }

  @Test
  void exitCodeDiferenteDeZeroViraVideoInvalidoComASaidaDeErro() throws IOException {
    Path fake = script("echo 'moov atom not found' >&2; exit 1");

    assertThatThrownBy(
            () -> extractor(fake, Duration.ofSeconds(10)).extract(tmp.resolve("x"), 1, framesDir()))
        .isInstanceOf(ProcessingException.class)
        .hasMessageContaining("moov atom not found")
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.INVALID_VIDEO);
  }

  @Test
  void processoQueNaoTerminaNoPrazoEMortoEViraTimeout() throws IOException {
    Path fake = script("sleep 30");

    assertThatThrownBy(
            () -> extractor(fake, Duration.ofMillis(300)).extract(tmp.resolve("x"), 1, framesDir()))
        .isInstanceOf(ProcessingException.class)
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.EXTRACTION_TIMEOUT);
  }

  @Test
  void binarioInexistenteViraFfmpegIndisponivel() {
    assertThatThrownBy(
            () ->
                extractor(tmp.resolve("nao-existe"), Duration.ofSeconds(5))
                    .extract(tmp.resolve("x"), 1, framesDir()))
        .isInstanceOf(ProcessingException.class)
        .extracting(e -> ((ProcessingException) e).code())
        .isEqualTo(ErrorCode.FFMPEG_UNAVAILABLE);
  }

  @Test
  void extraiUmFramePorSegundoDeUmMp4RealQuandoHaFfmpeg() throws Exception {
    assumeTrue(ffmpegAvailable(), "ffmpeg nao esta no PATH");
    Path video = tmp.resolve("sample.mp4");
    try (var in = getClass().getResourceAsStream("/fixtures/sample-3s.mp4")) {
      Files.copy(in, video);
    }

    int count =
        new FfmpegFrameExtractor(TestProperties.defaults(tmp)).extract(video, 1, framesDir());

    // 3s a 1 fps; o ffmpeg pode incluir o frame do instante final
    assertThat(count).isBetween(3, 4);
  }

  static boolean ffmpegAvailable() {
    try {
      return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
