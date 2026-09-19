package br.com.fiap.hackaton.processor.application.port;

import java.nio.file.Path;

/**
 * Extrai os frames de um video como imagens.
 *
 * <p>Porta de dominio: o caso de uso nao conhece o FFmpeg, o que permite testa-lo sem o binario
 * instalado (WRK-3).
 */
public interface FrameExtractor {

  /**
   * @param video arquivo de video local
   * @param fps frames por segundo a extrair
   * @param outputDir diretorio vazio onde as imagens serao escritas
   * @return quantidade de frames gerados
   */
  int extract(Path video, int fps, Path outputDir);
}
