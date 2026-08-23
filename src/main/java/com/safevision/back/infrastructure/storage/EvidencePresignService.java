package com.safevision.back.infrastructure.storage;

import com.safevision.back.application.ports.out.EvidenceStoragePort;
import com.safevision.back.infrastructure.config.S3Properties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * Genera URLs prefirmadas de S3 (solo lectura) para la evidencia que sube
 * el módulo CV — el backend nunca escribe al bucket, solo lee bajo demanda.
 * Se reutiliza tanto para el envío de la foto a Telegram como para el
 * endpoint de "ver evidencia" del frontend.
 */
@Service
public class EvidencePresignService implements EvidenceStoragePort {

    private final S3Presigner presigner;
    private final S3Properties properties;

    @Autowired
    public EvidencePresignService(S3Properties properties) {
        this(properties, S3Presigner.builder().region(Region.of(properties.region())).build());
    }

    /** Constructor con presigner inyectable, para poder mockearlo en tests unitarios. */
    EvidencePresignService(S3Properties properties, S3Presigner presigner) {
        this.properties = properties;
        this.presigner = presigner;
    }

    /** Retorna una URL GET prefirmada, válida por {@code s3.presign-ttl-minutes} (default 15 min). */
    @Override
    public String presignGetUrl(String storageKey) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(properties.presignTtlMinutes()))
                .getObjectRequest(getRequest)
                .build();

        return presigner.presignGetObject(presignRequest).url().toString();
    }

    @PreDestroy
    void close() {
        presigner.close();
    }
}
