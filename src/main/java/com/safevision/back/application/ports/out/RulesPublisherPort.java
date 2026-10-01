package com.safevision.back.application.ports.out;

import java.util.List;

/**
 * Puerto driven: notifica (best-effort, vía HTTP) las reglas EPP vigentes de
 * una obra para que el módulo CV las reciba sin tener que consultarlas.
 */
public interface RulesPublisherPort {

    void publishRules(Long siteId, List<String> requiredEppCodes, int cooldownSeconds);
}
