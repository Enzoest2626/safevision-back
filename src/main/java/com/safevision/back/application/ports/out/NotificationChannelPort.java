package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import reactor.core.publisher.Mono;

/**
 * Puerto driven: envía la alerta de un incidente EPP por el canal que sea
 * (hoy Telegram). Dos formas de entregar la foto: bytes inline (flujo HTTP
 * legacy) o URL prefirmada de S3 (flujo MQTT/CV nuevo).
 */
public interface NotificationChannelPort {

    Mono<Void> sendIncidentAlert(String chatId, Incident incident, Camera camera, Site site,
                                  String zoneName, String frameB64);

    Mono<Void> sendIncidentAlertByUrl(String chatId, Incident incident, Camera camera, Site site,
                                       String zoneName, String photoUrl);
}
