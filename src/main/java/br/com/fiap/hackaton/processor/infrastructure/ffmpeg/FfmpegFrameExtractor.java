package br.com.fiap.hackaton.processor.infrastructure.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.com.fiap.hackaton.processor.application.port.FrameExtractor;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import br.com.fiap.hackaton.processor.infrastructure.config.ProcessorProperties;

/**
 * Adaptador FFmpeg da porta {@link FrameExtractor} (WRK-3).
 *
 * <p>Roda {@code ffmpeg -vf fps=N} como processo externo, com timeout, exit code checado e a saida
 * de erro guardada em arquivo (um pipe nao lido pode travar o processo quando enche).
 */
@Component
public class FfmpegFrameExtractor implements FrameExtractor {

  static final String FRAME_PREFIX = "frame_";
  private static final Logger log = LoggerFactory.getLogger(FfmpegFrameExtractor.class);
  private static final int STDERR_TAIL_CHARS = 400;

  private final ProcessorProperties.Ffmpeg config;

  public FfmpegFrameExtractor(ProcessorProperties properties) {
    this.config = properties.ffmpeg();
  }

  @Override
  public int extract(Path video, int fps, Path outputDir) {
    List<String> command =
        List.of(
            config.binary(),
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-y",
            "-i",
            video.toString(),
            "-vf",
            "fps=" + fps,
            "-q:v",
            "2",
            outputDir.resolve(FRAME_PREFIX + "%05d.jpg").toString());
    Path ffmpegLog = outputDir.resolveSibling("ffmpeg.log");

    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ffmpegLog.toFile())
              .start();
    } catch (IOException e) {
      throw new ProcessingException(
          ErrorCode.FFMPEG_UNAVAILABLE, "Nao foi possivel executar " + config.binary(), e);
    }

    try {
      if (!process.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException(
            ErrorCode.EXTRACTION_TIMEOUT,
            "FFmpeg nao terminou em " + config.timeout().toSeconds() + "s");
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new ProcessingException(ErrorCode.UNEXPECTED, "Extracao interrompida", e);
    }

    int exit = process.exitValue();
    if (exit != 0) {
      String detail = tail(ffmpegLog);
      log.warn("FFmpeg saiu com codigo {}: {}", exit, detail);
      throw new ProcessingException(
          ErrorCode.INVALID_VIDEO, "FFmpeg falhou (exit " + exit + "): " + detail);
    }
    return countFrames(outputDir);
  }

  private static int countFrames(Path outputDir) {
    try (Stream<Path> files = Files.list(outputDir)) {
      return (int) files.filter(f -> f.getFileName().toString().startsWith(FRAME_PREFIX)).count();
    } catch (IOException e) {
      throw new ProcessingException(ErrorCode.WORKDIR_ERROR, "Falha ao listar os frames", e);
    }
  }

  private static String tail(Path file) {
    try {
      String text = Files.readString(file, StandardCharsets.UTF_8).strip();
      return text.length() <= STDERR_TAIL_CHARS
          ? text
          : text.substring(text.length() - STDERR_TAIL_CHARS);
    } catch (IOException e) {
      return "(sem saida do ffmpeg)";
    }
  }
}
