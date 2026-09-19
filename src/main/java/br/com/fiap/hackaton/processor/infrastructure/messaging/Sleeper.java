package br.com.fiap.hackaton.processor.infrastructure.messaging;

import java.time.Duration;

/** Espera entre tentativas. Existe como interface para os testes nao dormirem de verdade. */
@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;

  static Sleeper threadSleep() {
    return duration -> Thread.sleep(duration.toMillis());
  }
}
