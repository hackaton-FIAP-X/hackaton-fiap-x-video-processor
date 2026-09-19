package br.com.fiap.hackaton.processor;

import java.util.UUID;

import br.com.fiap.hackaton.processor.domain.VideoJob;

public final class TestJobs {

  private TestJobs() {}

  public static VideoJob job() {
    UUID userId = UUID.randomUUID();
    UUID videoId = UUID.randomUUID();
    return new VideoJob(
        videoId,
        userId,
        "fiapx/inputs/%s/%s/aula.mp4".formatted(userId, videoId),
        "aula.mp4",
        1,
        "trace-1");
  }
}
