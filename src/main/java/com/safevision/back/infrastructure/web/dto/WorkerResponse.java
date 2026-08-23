package com.safevision.back.infrastructure.web.dto;

import com.safevision.back.domain.model.Worker;

import java.time.LocalDateTime;

public record WorkerResponse(
        Long id,
        Long siteId,
        int code,
        String firstName,
        String lastName,
        String role,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static WorkerResponse from(Worker worker) {
        return new WorkerResponse(
                worker.id(), worker.siteId(), worker.code(),
                worker.firstName(), worker.lastName(), worker.role(), worker.active(),
                worker.createdAt(), worker.createdBy(), worker.updatedAt(), worker.updatedBy()
        );
    }
}
