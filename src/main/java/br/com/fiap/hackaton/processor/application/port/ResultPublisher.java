package br.com.fiap.hackaton.processor.application.port;

import br.com.fiap.hackaton.processor.domain.ErrorCode;
import br.com.fiap.hackaton.processor.domain.ProcessingResult;
import br.com.fiap.hackaton.processor.domain.VideoJob;

/**
 * Publica o resultado para o video-service. As implementacoes so retornam depois que o broker
 * confirmou a mensagem; em qualquer outra situacao lancam {@link PublishException}.
 */
public interface ResultPublisher {

  void publishProcessed(VideoJob job, ProcessingResult result);

  void publishFailed(VideoJob job, ErrorCode code, String message, int attempts);

  class PublishException extends RuntimeException {
    public PublishException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
