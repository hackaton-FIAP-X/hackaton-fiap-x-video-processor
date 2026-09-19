package br.com.fiap.hackaton.processor.infrastructure.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipPackagerTest {

  @TempDir Path tmp;

  @Test
  void empacotaTodosOsArquivosEmOrdemComOConteudoIntacto() throws IOException {
    Path frames = Files.createDirectory(tmp.resolve("frames"));
    Files.writeString(frames.resolve("frame_00002.jpg"), "dois");
    Files.writeString(frames.resolve("frame_00001.jpg"), "um");
    Files.createDirectory(frames.resolve("subdir-ignorado"));
    Path zip = tmp.resolve("out.zip");

    int packed = new ZipPackager().pack(frames, zip);

    assertThat(packed).isEqualTo(2);
    List<String> names = new ArrayList<>();
    List<String> contents = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        names.add(e.getName());
        contents.add(new String(in.readAllBytes()));
      }
    }
    assertThat(names).containsExactly("frame_00001.jpg", "frame_00002.jpg");
    assertThat(contents).containsExactly("um", "dois");
  }

  @Test
  void diretorioVazioGeraZipVazio() throws IOException {
    Path frames = Files.createDirectory(tmp.resolve("vazio"));

    assertThat(new ZipPackager().pack(frames, tmp.resolve("v.zip"))).isZero();
    assertThat(tmp.resolve("v.zip")).exists();
  }
}
