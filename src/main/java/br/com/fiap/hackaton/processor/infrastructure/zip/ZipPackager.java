package br.com.fiap.hackaton.processor.infrastructure.zip;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

/**
 * Empacota os frames num ZIP em streaming (WRK-4): cada imagem e copiada direto do disco para o
 * arquivo, entao a memoria usada nao cresce com a duracao do video.
 */
@Component
public class ZipPackager {

  /**
   * @return quantidade de arquivos empacotados
   */
  public int pack(Path sourceDir, Path zipFile) throws IOException {
    List<Path> files;
    try (Stream<Path> listing = Files.list(sourceDir)) {
      files = listing.filter(Files::isRegularFile).sorted().toList();
    }
    try (OutputStream out = Files.newOutputStream(zipFile);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      // JPEG ja e comprimido: nivel alto so gasta CPU sem reduzir o arquivo.
      zip.setLevel(Deflater.BEST_SPEED);
      for (Path file : files) {
        zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
        Files.copy(file, zip);
        zip.closeEntry();
      }
    }
    return files.size();
  }
}
