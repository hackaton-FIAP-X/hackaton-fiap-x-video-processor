# video-processor

Worker assíncrono do FIAP X: consome o evento `video.uploaded`, extrai os frames
do vídeo com FFmpeg, empacota tudo num ZIP e publica `video.processed` (ou
`video.failed`) para o video-service.

## Fluxo

```
video-service ──video.uploaded──▶ fila video.processing ──▶ worker
                                                              │ baixa o vídeo do S3/MinIO
                                                              │ ffmpeg -vf fps=N  → frame_00001.jpg ...
                                                              │ ZIP em streaming  → fiapx/outputs/{userId}/{videoId}.zip
worker ──video.processed / video.failed──▶ fila video.status ──▶ video-service
```

Contrato (exchange, filas, payloads e chaves no storage) igual ao do video-service:
`RabbitMqConfig`, `*Event` e `StorageKey` de lá.

## Garantias

| | Como |
|---|---|
| Não perde vídeo | ack manual, só depois de o resultado ser **confirmado** pelo broker (publisher confirms). Broker fora na hora de publicar → a mensagem volta para a fila |
| Pods dividem o trabalho | `prefetch=1`: cada réplica pega um vídeo por vez, então o HPA distribui a carga |
| Reentrega segura | se o ZIP já existe, republica o sucesso (frameCount vem da metadata do objeto) sem rodar o FFmpeg |
| Falha transitória | 3 tentativas com backoff exponencial (storage fora, disco) |
| Falha do vídeo | arquivo corrompido falha na hora: publica `video.failed` e a mensagem vai para `video.processing.dlq` |
| Disco limpo | diretório temporário por vídeo, apagado em `finally` |

Códigos de erro no `video.failed`: `INVALID_VIDEO`, `NO_FRAMES`, `INPUT_NOT_FOUND`,
`EXTRACTION_TIMEOUT`, `FFMPEG_UNAVAILABLE`, `STORAGE_ERROR`, `WORKDIR_ERROR`,
`UNEXPECTED`, `INVALID_MESSAGE`.

## Configuração

| Variável | Default | |
|---|---|---|
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | `guest` / `guest` | |
| `RABBITMQ_SSL_ENABLED` | `false` | `true` no Amazon MQ (AMQPS, porta 5671) |
| `STORAGE_ENDPOINT` | vazio | vazio = S3 da AWS; no local, o MinIO |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | vazio | vazio = cadeia padrão da AWS (`LabRole` no Learner Lab) |
| `STORAGE_BUCKET` / `STORAGE_REGION` | `fiapx` / `us-east-1` | |
| `PROCESSOR_CONCURRENCY` | `1` | consumidores por pod |
| `PROCESSOR_MAX_ATTEMPTS` | `3` | |
| `FFMPEG_TIMEOUT` | `10m` | por vídeo |

Métricas em `/actuator/prometheus`: `videos_processed_total`,
`video_processing_seconds` (histograma), `videos_failed_total{error_code}`,
`videos_reused_total`.

## Rodando

```bash
./mvnw verify            # testes + cobertura (gate 80%). Integração exige Docker e ffmpeg
docker compose up        # worker + RabbitMQ + MinIO (dev, hot reload)
```

Ambiente completo (auth, video, worker, observabilidade): repo `infra`
(`make up` no kind ou `make compose-up`). Imagem de produção: `Dockerfile.prod`.
