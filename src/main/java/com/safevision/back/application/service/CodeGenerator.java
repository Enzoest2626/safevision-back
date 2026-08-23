package com.safevision.back.application.service;

import java.util.UUID;

/**
 * Genera un código corto y legible cuando el cliente no especifica uno al
 * crear una obra/zona/cámara — usado como identificador estable en los
 * topics MQTT (ver CLAUDE.md), así que debe existir siempre.
 */
final class CodeGenerator {

    private CodeGenerator() {}

    static String generate(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
