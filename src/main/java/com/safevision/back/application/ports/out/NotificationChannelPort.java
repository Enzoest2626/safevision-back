package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import reactor.core.publisher.Mono;

/**
 * Puerto driven: envía la alerta de un incidente EPP por el canal que sea
 * (hoy Telegram). Dos formas de entregar la foto: bytes inline (flujo HTTP
 * legacy) o URL prefirmada de S3 (flujo CV nuevo, evidencia en S3). Además
 * permite mandar un mensaje de texto plano (reporte diario, vinculación).
 */
public interface NotificationChannelPort {

    Mono<Void> sendIncidentAlert(String chatId, Incident incident, Worker worker, Camera camera, Site site,
                                  String zoneName, String frameB64);

    Mono<Void> sendIncidentAlertByUrl(String chatId, Incident incident, Worker worker, Camera camera, Site site,
                                       String zoneName, String photoUrl);

    /** Mensaje de texto sin foto — usado por el reporte diario (HU12). */
    Mono<Void> sendTextMessage(String chatId, String text);
}
