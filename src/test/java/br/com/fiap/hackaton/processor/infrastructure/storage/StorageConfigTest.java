package br.com.fiap.hackaton.processor.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

class StorageConfigTest {

  @Test
  void comChavesUsaCredenciaisEstaticas() {
    var props = new StorageProperties("http://minio:9000", "ak", "sk", "fiapx", "us-east-1");

    assertThat(StorageConfig.credentialsProvider(props))
        .isInstanceOf(StaticCredentialsProvider.class);
    assertThat(props.hasCustomEndpoint()).isTrue();
  }

  @Test
  void semChavesUsaACadeiaPadraoDaAwsParaOLabRole() {
    var props = new StorageProperties("", "", null, "fiapx", "us-east-1");

    assertThat(StorageConfig.credentialsProvider(props))
        .isInstanceOf(DefaultCredentialsProvider.class);
    assertThat(props.hasCustomEndpoint()).isFalse();
  }

  @Test
  void clienteSobeComESemEndpointCustomizado() {
    var config = new StorageConfig();

    config
        .s3Client(new StorageProperties("http://minio:9000", "ak", "sk", "b", "us-east-1"))
        .close();
    config.s3Client(new StorageProperties(null, "ak", "sk", "b", "sa-east-1")).close();
  }
}
