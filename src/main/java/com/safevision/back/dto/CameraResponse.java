package com.safevision.back.dto;

import com.safevision.back.model.Camera;

import java.time.LocalDateTime;

public record CameraResponse(
        Long id,
        Long siteId,
        String code,
        String name,
        String ipAddress,
        String rtspUrl,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static CameraResponse from(Camera camera) {
        return new CameraResponse(
                camera.id(), camera.siteId(), camera.code(), camera.name(),
                camera.ipAddress(), camera.rtspUrl(), camera.active(),
                camera.createdAt(), camera.createdBy(), camera.updatedAt(), camera.updatedBy()
        );
    }
}
