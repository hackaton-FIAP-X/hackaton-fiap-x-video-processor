package br.com.fiap.hackaton.processor.domain;

/** Falha de processamento com um {@link ErrorCode} que vai para o evento {@code video.failed}. */
public class ProcessingException extends RuntimeException {

  private final ErrorCode code;

  public ProcessingException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ProcessingException(ErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
