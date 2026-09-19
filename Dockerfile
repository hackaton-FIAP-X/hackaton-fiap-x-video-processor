# Imagem de DESENVOLVIMENTO (hot reload via docker-compose.yml deste repo).
# Producao: Dockerfile.prod.
FROM eclipse-temurin:21-jdk-alpine

# o worker chama o ffmpeg como processo externo
RUN apk add --no-cache ffmpeg

WORKDIR /app

COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

COPY src ./src

EXPOSE 8082

ENTRYPOINT ["./mvnw", "spring-boot:run", "-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"]