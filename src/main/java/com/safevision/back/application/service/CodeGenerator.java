package com.safevision.back.application.service;

import java.util.UUID;

/**
 * Genera un código corto y legible cuando el cliente no especifica uno al
 * crear una obra/zona/cámara — identificador de negocio estable, así que
 * debe existir siempre (ver CLAUDE.md).
 */
final class CodeGenerator {

    private CodeGenerator() {}

    static String generate(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
