package com.safevision.back.service;

import com.safevision.back.config.CvProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;

class CvNotificationServiceTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void sinReloadUrlConfigurada_noHaceNadaYNoLanzaExcepcion(String reloadUrl) {
        CvNotificationService service = new CvNotificationService(new CvProperties(reloadUrl));

        assertThatCode(service::notifyRulesChanged).doesNotThrowAnyException();
    }

    @Test
    void conReloadUrlInvalida_noPropagaExcepcion() {
        // best-effort: incluso con una URL con formato invalido, no debe
        // interrumpir el flujo de updateForSite() — el error queda en el
        // consumer de error del subscribe(), silencioso por diseño.
        CvNotificationService service = new CvNotificationService(new CvProperties("no-es-una-url"));

        assertThatCode(service::notifyRulesChanged).doesNotThrowAnyException();
    }
}
