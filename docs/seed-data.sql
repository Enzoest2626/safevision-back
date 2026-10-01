-- ============================================================
-- SafeVision — Datos semilla para demo en vivo
-- Ejecutar DESPUES de docs/init-schema.sql, contra una BD vacia.
-- Idempotente: seguro volver a correrlo (usa ON CONFLICT DO NOTHING).
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- CATÁLOGOS
-- ────────────────────────────────────────────────────────────

INSERT INTO user_roles (code, name) VALUES
    ('ADMIN', 'Administrador'),
    ('SUPERVISOR', 'Supervisor')
ON CONFLICT (code) DO NOTHING;

INSERT INTO notification_channels (code, name) VALUES
    ('TELEGRAM', 'Telegram')
ON CONFLICT (code) DO NOTHING;

INSERT INTO notification_statuses (code, name) VALUES
    ('PENDING', 'Pendiente'),
    ('SENT', 'Enviada'),
    ('FAILED', 'Fallida')
ON CONFLICT (code) DO NOTHING;

INSERT INTO epp_parameters (code, name) VALUES
    ('casco', 'Casco'),
    ('chaleco', 'Chaleco'),
    ('guantes', 'Guantes')
ON CONFLICT (code) DO NOTHING;

-- ────────────────────────────────────────────────────────────
-- USUARIO ADMIN (bootstrap)
-- POST /api/v1/users ahora exige JWT, y el JWT solo se obtiene logueado
-- con un usuario que ya exista — sin esta fila nadie puede entrar nunca.
-- Password: SafeVision2026! (hash BCrypt generado con el mismo
-- BCryptPasswordEncoder de la app — cambiar apenas se tenga un flujo
-- real de gestión de usuarios, esto es solo para arrancar la demo).
-- ────────────────────────────────────────────────────────────

INSERT INTO users (username, email, password_hash, role_id, phone)
SELECT 'admin', 'admin@safevision.com',
       '$2a$10$sIeD1mqXoTGaAe7iYFpL3ey7M2wk8JW/m1fBU25misuVT/CuGY3lK',
       id, NULL
FROM user_roles WHERE code = 'ADMIN'
ON CONFLICT (username) DO NOTHING;

-- ────────────────────────────────────────────────────────────
-- OBRA DEMO
-- name/code deben coincidir con SITE_NAME/CAMERA_ID del .env del modulo CV
-- (ver .env.example: SITE_NAME=Obra-Principal, CAMERA_ID=CAM-01) — el CV
-- los manda en cada evento HTTP para que el backend resuelva a que
-- obra/camara corresponde.
-- ────────────────────────────────────────────────────────────

INSERT INTO sites (code, name, location) VALUES
    ('OBRA-PRINCIPAL', 'Obra-Principal', 'Demo — sustentacion en vivo')
ON CONFLICT (name) DO NOTHING;

INSERT INTO zones (site_id, code, name)
SELECT id, 'ZONA-PRINCIPAL', 'Zona Principal' FROM sites WHERE name = 'Obra-Principal'
ON CONFLICT (site_id, name) DO NOTHING;

INSERT INTO cameras (site_id, zone_id, code, name)
SELECT s.id, z.id, 'CAM-01', 'Camara Obra-Principal'
FROM sites s
JOIN zones z ON z.site_id = s.id AND z.name = 'Zona Principal'
WHERE s.name = 'Obra-Principal'
ON CONFLICT (code) DO NOTHING;

-- ────────────────────────────────────────────────────────────
-- TRABAJADORES
-- worker.code = track_id de ByteTrack (efimero por sesion, crece
-- desde 1). Se seedean 500 codigos para cubrir cualquier sesion de
-- demo sin chocar con la FK incidents.worker_id.
-- ────────────────────────────────────────────────────────────

INSERT INTO workers (site_id, code, first_name, last_name, role)
SELECT s.id, gs, 'Trabajador', gs::text, 'Operario'
FROM sites s, generate_series(1, 500) AS gs
WHERE s.name = 'Obra-Principal'
ON CONFLICT (code) DO NOTHING;

-- ────────────────────────────────────────────────────────────
-- CONTACTO DE OBRA (alertas Telegram)
-- Opcional: si no se inserta ningun site_contacts con
-- telegram_chat_id, IncidentService usa el fallback TELEGRAM_CHAT_ID
-- del entorno (ver src/main/java/.../IncidentService.java). Para la
-- demo alcanza con configurar esa env var — descomentar y completar
-- solo si se quiere un contacto nominal en la tabla.
-- ────────────────────────────────────────────────────────────

-- INSERT INTO site_contacts (site_id, name, phone, telegram_chat_id)
-- SELECT id, 'Supervisor Demo', '+51900000000', '<tu_telegram_chat_id>'
-- FROM sites WHERE name = 'Obra-Principal';

-- Nota: site_epp_requirements se deja vacio a proposito — sin filas
-- para esta obra, el backend aplica el fallback fail-safe (los 3 EPP
-- del catalogo), igual que ComplianceRules.default() en el modulo CV.
