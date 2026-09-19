package br.com.fiap.hackaton.processor.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.fiap.hackaton.processor.application.port.FrameExtractor;
import br.com.fiap.hackaton.processor.application.port.VideoStorage;
import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingException;
import br.com.fiap.hackaton.processor.domain.ProcessingResult;
import br.com.fiap.hackaton.processor.domain.VideoJob;
import br.com.fiap.hackaton.processor.infrastructure.config.ProcessorProperties;
import br.com.fiap.hackaton.processor.infrastructure.zip.ZipPackager;

/**
 * Caso de uso: baixa o video, extrai os frames, empacota em ZIP e publica no storage.
 *
 * <p>Idempotente (WRK-7): se o ZIP do video ja existe, devolve o resultado dele sem reprocessar.
 * Cobre o caso do pod morrer depois de subir o ZIP e antes do ack da mensagem.
 */
@Service
public class VideoProcessingService {

  private static final Logger log = LoggerFactory.getLogger(VideoProcessingService.class);
  private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,8}");

  private final VideoStorage storage;
  private final FrameExtractor frameExtractor;
  private final ZipPackager zipPackager;
  private final ProcessorProperties properties;

  public VideoProcessingService(
      VideoStorage storage,
      FrameExtractor frameExtractor,
      ZipPackager zipPackager,
      ProcessorProperties properties) {
    this.storage = storage;
    this.frameExtractor = frameExtractor;
    this.zipPackager = zipPackager;
    this.properties = properties;
  }

  public ProcessingResult process(VideoJob job) {
    String zipKey = job.zipKey();
    OptionalInt existing = storage.findZipFrameCount(zipKey);
    if (existing.isPresent()) {
      log.info(
          "Video {} ja processado ({} frames); reaproveitando o ZIP [trace={}]",
          job.videoId(),
          existing.getAsInt(),
          job.traceId());
      return new ProcessingResult(zipKey, existing.getAsInt(), true);
    }

    Path workDir = createWorkDir(job);
    try {
      // O nome original vem do usuario: nunca vira caminho, so empresta a extensao.
      Path input = workDir.resolve("input" + safeExtension(job.originalFilename()));
      storage.download(job.storageKey(), input);

      Path framesDir = Files.createDirectory(workDir.resolve("frames"));
      int fps = Math.min(job.fps(), properties.ffmpeg().maxFps());
      int frames = frameExtractor.extract(input, fps, framesDir);
      if (frames == 0) {
        throw new ProcessingException(ErrorCode.NO_FRAMES, ErrorCode.NO_FRAMES.description());
      }

      Path zip = workDir.resolve("frames.zip");
      int packed = zipPackager.pack(framesDir, zip);
      storage.uploadZip(zipKey, zip, packed);
      log.info(
          "Video {} processado: {} frames em {} [trace={}]",
          job.videoId(),
          packed,
          zipKey,
          job.traceId());
      return new ProcessingResult(zipKey, packed, false);
    } catch (IOException e) {
      throw new ProcessingException(
          ErrorCode.WORKDIR_ERROR, "Falha de disco no diretorio de trabalho", e);
    } finally {
      deleteRecursively(workDir);
    }
  }

  private Path createWorkDir(VideoJob job) {
    try {
      Path base = properties.workDirOrTemp();
      Files.createDirectories(base);
      return Files.createTempDirectory(base, "video-" + job.videoId() + "-");
    } catch (IOException e) {
      throw new ProcessingException(
          ErrorCode.WORKDIR_ERROR, "Nao foi possivel criar o diretorio de trabalho", e);
    }
  }

  static String safeExtension(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    if (dot < 0 || dot == filename.length() - 1) {
      return "";
    }
    String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    return SAFE_EXTENSION.matcher(ext).matches() ? "." + ext : "";
  }

  /** WRK-2: nada fica para tras no disco do pod, com sucesso ou com erro. */
  static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
              Files.deleteIfExists(d);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      log.warn("Nao foi possivel limpar {}: {}", dir, e.getMessage());
    }
  }
}
