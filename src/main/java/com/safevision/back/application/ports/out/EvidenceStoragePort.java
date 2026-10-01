package com.safevision.back.application.ports.out;

/**
 * Puerto driven: genera URLs de lectura de corta duración para evidencia
 * (foto/clip) subida a S3 por el módulo CV — el backend nunca escribe.
 */
public interface EvidenceStoragePort {

    String presignGetUrl(String storageKey);
}
