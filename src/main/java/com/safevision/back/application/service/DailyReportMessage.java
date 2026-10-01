package com.safevision.back.application.service;

import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Arma el texto en español del reporte diario (HU12) de UNA obra: total de incumplimientos,
 * subtotal por tipo de EPP y desglose por cámara — sin listar cada evento (eso vive en
 * /api/v1/incidents y en el dashboard). Día vacío → mensaje explícito de "sin incumplimientos".
 * Lo más cargado primero, para que se lea de un vistazo.
 */
public final class DailyReportMessage {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private DailyReportMessage() {}

    public static String build(Site site, LocalDate day, List<Incident> incidents, Map<Long, String> cameras) {
        StringBuilder text = new StringBuilder("📊 <b>Reporte diario SafeVision</b>\nObra: ")
                .append(escape(site.name())).append("\nFecha: ").append(day.format(DAY_FORMAT)).append("\n\n");
        if (incidents.isEmpty()) {
            return text.append("✅ Sin incumplimientos en el día de hoy.").toString();
        }

        Map<String, Long> byEpp = new TreeMap<>();
        Map<String, Long> byCamera = new TreeMap<>();
        for (Incident incident : incidents) {
            for (String epp : incident.missingEpp()) {
                byEpp.merge(epp, 1L, Long::sum);
            }
            byCamera.merge(cameraName(incident, cameras), 1L, Long::sum);
        }

        text.append("<b>Total de incumplimientos: ").append(incidents.size()).append("</b>\n\n");
        text.append("<b>Por EPP</b>\n");
        appendRanked(text, byEpp);
        text.append("\n<b>Por cámara</b>\n");
        appendRanked(text, byCamera);
        return text.toString();
    }

    /** Ordena de mayor a menor (empate: alfabético) y lo vuelca como viñetas. */
    private static void appendRanked(StringBuilder text, Map<String, Long> counts) {
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> text.append("• ").append(escape(entry.getKey()))
                        .append(": ").append(entry.getValue()).append("\n"));
    }

    private static String cameraName(Incident incident, Map<Long, String> cameras) {
        return cameras.getOrDefault(incident.cameraId(), "cámara " + incident.cameraId());
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
