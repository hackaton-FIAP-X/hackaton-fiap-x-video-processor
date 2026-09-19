package br.com.fiap.hackaton.processor.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DomainTest {

  private final UUID user = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private final UUID video = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void zipKeySegueOLayoutDoVideoService() {
    var job = new VideoJob(video, user, "fiapx/inputs/x", "a.mp4", 1, "t");

    assertThat(job.zipKey())
        .isEqualTo(
            "fiapx/outputs/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.zip");
  }

  @Test
  void jobInvalidoERecusado() {
    assertThatThrownBy(() -> new VideoJob(null, user, "k", "a", 1, "t"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new VideoJob(video, user, " ", "a", 1, "t"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VideoJob(video, user, "k", "a", 0, "t"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void falhasDoVideoNaoSaoRepetidasEFalhasDeInfraSao() {
    assertThat(ErrorCode.INVALID_VIDEO.retryable()).isFalse();
    assertThat(ErrorCode.NO_FRAMES.retryable()).isFalse();
    assertThat(ErrorCode.STORAGE_ERROR.retryable()).isTrue();
    assertThat(new ProcessingException(ErrorCode.UNEXPECTED, "m", new RuntimeException()).code())
        .isEqualTo(ErrorCode.UNEXPECTED);
  }
}
