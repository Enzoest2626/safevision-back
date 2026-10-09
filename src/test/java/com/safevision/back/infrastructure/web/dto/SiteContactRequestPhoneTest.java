package com.safevision.back.infrastructure.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Verifica el formato de phone de {@link SiteContactRequest} contra la longitud
 * de la columna site_contacts.phone (VARCHAR(20)): el teléfono no puede superar
 * 20 caracteres EN TOTAL (incluido el '+'), o la BD rechazaría la fila con 500.
 */
@DisplayName("SiteContactRequest — validación de phone (máximo 20 caracteres en total)")
class SiteContactRequestPhoneTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    // "+51" (3) + 17 dígitos = 20 caracteres en total
    private static final String PHONE_20 = "+51" + "9".repeat(17);
    // Un dígito de más: 21 caracteres — el caso que antes pasaba la validación
    // y reventaba contra el VARCHAR(20) de la base.
    private static final String PHONE_21 = "+51" + "9".repeat(18);

    private static final String MENSAJE_FORMATO =
            "El teléfono no tiene un formato válido (ejemplo: +51 999 999 999)";

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<ConstraintViolation<SiteContactRequest>> validar(String phone) {
        return validator.validate(new SiteContactRequest("Supervisor", phone, null));
    }

    private static List<String> mensajes(Set<ConstraintViolation<SiteContactRequest>> violaciones) {
        return violaciones.stream().map(ConstraintViolation::getMessage).toList();
    }

    @Test
    @DisplayName("+51 999 999 999 es válido")
    void conPrefijoInternacionalYSeparadores_esValido() {
        assertThat(validar("+51 999 999 999")).isEmpty();
    }

    @Test
    @DisplayName("999 999 999 (sin '+') es válido")
    void sinPrefijoInternacional_esValido() {
        assertThat(validar("999 999 999")).isEmpty();
    }

    @Test
    @DisplayName("un teléfono de exactamente 20 caracteres (incluido el '+') es válido")
    void deVeinteCaracteres_esValido() {
        assertThat(validar(PHONE_20)).isEmpty();
    }

    @Test
    @DisplayName("un teléfono de 21 caracteres (con '+') es inválido")
    void deVeintiunCaracteres_esInvalido() {
        assertThat(mensajes(validar(PHONE_21))).containsExactly(MENSAJE_FORMATO);
    }

    @Test
    @DisplayName("un teléfono con letras es inválido")
    void conLetras_esInvalido() {
        assertThat(mensajes(validar("abc1234567"))).containsExactly(MENSAJE_FORMATO);
    }

    @Test
    @DisplayName("un teléfono de menos de 7 dígitos es inválido")
    void conMenosDeSieteDigitos_esInvalido() {
        assertThat(mensajes(validar("12345"))).containsExactly(MENSAJE_FORMATO);
    }

    @Test
    @DisplayName("un teléfono vacío es inválido")
    void vacio_esInvalido() {
        assertThat(validar("")).isNotEmpty();
    }
}
