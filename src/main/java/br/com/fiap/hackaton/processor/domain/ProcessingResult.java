package br.com.fiap.hackaton.processor.domain;

/**
 * Resultado de um processamento.
 *
 * @param reused true quando o ZIP ja existia (reentrega da mensagem) e o FFmpeg nao rodou
 */
public record ProcessingResult(String zipKey, int frameCount, boolean reused) {}
