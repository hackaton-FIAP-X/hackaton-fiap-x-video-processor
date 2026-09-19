package br.com.fiap.hackaton.processor.domain;

/**
 * Motivos de falha publicados no evento {@code video.failed}.
 *
 * <p>{@code retryable} separa falha transitoria (storage fora, disco cheio), que vale tentar de
 * novo, de falha do proprio video (arquivo corrompido), que falharia igual em toda tentativa.
 */
public enum ErrorCode {
  INVALID_MESSAGE(false, "Mensagem de processamento invalida"),
  INPUT_NOT_FOUND(false, "Video original nao encontrado no storage"),
  INVALID_VIDEO(false, "Arquivo nao e um video valido ou esta corrompido"),
  NO_FRAMES(false, "Nenhum frame extraido do video"),
  EXTRACTION_TIMEOUT(false, "Extracao de frames excedeu o tempo limite"),
  FFMPEG_UNAVAILABLE(false, "FFmpeg indisponivel no worker"),
  STORAGE_ERROR(true, "Falha de comunicacao com o storage"),
  WORKDIR_ERROR(true, "Falha ao preparar o diretorio de trabalho"),
  UNEXPECTED(true, "Erro inesperado no processamento");

  private final boolean retryable;
  private final String description;

  ErrorCode(boolean retryable, String description) {
    this.retryable = retryable;
    this.description = description;
  }

  public boolean retryable() {
    return retryable;
  }

  public String description() {
    return description;
  }
}
