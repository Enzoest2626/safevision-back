package com.safevision.back.application.ports.out;

import java.util.List;

/**
 * Puerto driven: publica (retained, vía MQTT) las reglas EPP vigentes de
 * una obra para que el módulo CV las reciba sin tener que consultarlas.
 *
 * Direcciona por {@code siteCode} (no por ID numérico) — mismo criterio que
 * {@link CameraConfigPublisherPort}, ver CLAUDE.md.
 */
public interface RulesPublisherPort {

    void publishRules(String siteCode, List<String> requiredEppCodes);
}
