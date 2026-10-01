package com.safevision.back.application.service;

import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Arma el texto en español del reporte diario (HU12): total, agregado por EPP y una línea por
 * evento (hora, cámara, EPP faltante). Día vacío → mensaje explícito de "sin incumplimientos".
 */
public final class DailyReportMessage {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private DailyReportMessage() {}

    public static String build(Site site, LocalDate day, List<Incident> incidents, Map<Long, String> cameras) {
        StringBuilder text = new StringBuilder("📊 <b>Reporte diario SafeVision</b>\nObra: ")
                .append(escape(site.name())).append("\nFecha: ").append(day.format(DAY_FORMAT)).append("\n\n");
        if (incidents.isEmpty()) {
            return text.append("✅ Sin incumplimientos en el día de hoy.").toString();
        }
        Map<String, Long> byEpp = new TreeMap<>();
        for (Incident incident : incidents) {
            for (String epp : incident.missingEpp()) {
                byEpp.merge(epp, 1L, Long::sum);
            }
        }
        text.append("Total de incumplimientos: ").append(incidents.size()).append("\nPor EPP: ");
        byEpp.forEach((epp, count) -> text.append(epp).append(" ×").append(count).append(", "));
        text.setLength(text.length() - 2);
        incidents.stream().sorted(Comparator.comparing(Incident::occurredAt)).forEach(incident -> text.append("\n• ")
                .append(incident.occurredAt().format(HOUR_FORMAT)).append(" — ")
                .append(escape(cameras.getOrDefault(incident.cameraId(), "cámara " + incident.cameraId())))
                .append(" — ").append(String.join(", ", incident.missingEpp())));
        return text.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
