package com.safevision.back.application.dto.report;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turnos operativos del sistema (ver docs/ALCANCE.md): solo mañana y tarde.
 * El turno noche está excluido — la degradación de la imagen no permite un
 * análisis confiable, así que tampoco aparece en reportes.
 *
 * <ul>
 *   <li>{@code morning} = horas 06:00–11:59.</li>
 *   <li>{@code afternoon} = horas 12:00–20:59.</li>
 * </ul>
 * Las horas se evalúan sobre {@code incidents.occurred_at} (hora del
 * servidor; en prod Lima). Un valor distinto de {@code morning|afternoon}
 * se rechaza con 400.
 */
public enum ReportShift {

    MORNING(6, 11),
    AFTERNOON(12, 20);

    private final int fromHour;
    private final int toHour;

    ReportShift(int fromHour, int toHour) {
        this.fromHour = fromHour;
        this.toHour = toHour;
    }

    public int fromHour() {
        return fromHour;
    }

    public int toHour() {
        return toHour;
    }

    /**
     * Parsea el query param {@code shift}: null/vacío = sin filtro de turno.
     */
    public static ReportShift parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "morning" -> MORNING;
            case "afternoon" -> AFTERNOON;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "shift inválido: usa morning|afternoon");
        };
    }
}
