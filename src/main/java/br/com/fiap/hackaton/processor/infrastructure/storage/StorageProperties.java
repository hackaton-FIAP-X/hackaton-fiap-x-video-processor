package br.com.fiap.hackaton.processor.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Storage S3-compativel ({@code storage.*}).
 *
 * <p>No ambiente local {@code endpoint} aponta para o MinIO e as chaves sao fixas. Na AWS o
 * endpoint fica vazio (S3 padrao da regiao) e, sem chaves, as credenciais vem da cadeia padrao da
 * AWS — no Learner Lab, o papel {@code LabRole} dos nos do EKS.
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    @DefaultValue("fiapx") String bucket,
    @DefaultValue("us-east-1") String region) {

  public boolean hasCustomEndpoint() {
    return StringUtils.hasText(endpoint);
  }

  public boolean hasStaticCredentials() {
    return StringUtils.hasText(accessKey) && StringUtils.hasText(secretKey);
  }
}
