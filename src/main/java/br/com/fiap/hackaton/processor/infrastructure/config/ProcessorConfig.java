package br.com.fiap.hackaton.processor.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import br.com.fiap.hackaton.processor.infrastructure.messaging.Sleeper;

@Configuration
@EnableConfigurationProperties(ProcessorProperties.class)
public class ProcessorConfig {

  @Bean
  Sleeper sleeper() {
    return Sleeper.threadSleep();
  }
}
