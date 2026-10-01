package com.safevision.back.infrastructure.web.dto;

import com.safevision.back.domain.model.Incident;

import java.time.LocalDateTime;
import java.util.List;

public record IncidentResponse(
        Long id,
        Long workerId,
        Long cameraId,
        Long siteId,
        List<String> missingEpp,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {
    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.id(), incident.workerId(), incident.cameraId(), incident.siteId(),
                List.of(incident.missingEpp()), incident.occurredAt(), incident.createdAt()
        );
    }
}
