-- ============================================================
-- SafeVision — Esquema inicial de base de datos
-- PostgreSQL 16
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- CATÁLOGOS DE REFERENCIA
-- ────────────────────────────────────────────────────────────

CREATE TABLE user_roles (
    id   BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code VARCHAR(50)  NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE notification_channels (
    id   BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code VARCHAR(50)  NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE notification_statuses (
    id   BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code VARCHAR(50)  NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);

-- ────────────────────────────────────────────────────────────
-- DATOS MAESTROS
-- ────────────────────────────────────────────────────────────

-- code: identificador de negocio estable de la obra — se define solo al
-- crear (autogenerado si no se especifica) y es inmutable despues: el CV
-- lo usa para identificarse en cada evento que manda (ver CLAUDE.md).
-- cooldown_seconds: segundos minimos entre dos alertas del mismo trabajador+EPP
-- en el CV (ComplianceTracker) — editable desde el frontend en la seccion
-- Parametros (junto con los EPP requeridos), no desde el CRUD de Obras.
-- Se empuja al CV vía el mismo webhook /webhook/rules que los EPP requeridos.
CREATE TABLE sites (
    id               BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code             VARCHAR(50)  NOT NULL UNIQUE,
    name             VARCHAR(100) NOT NULL UNIQUE,
    location         VARCHAR(200),
    cooldown_seconds INTEGER      NOT NULL DEFAULT 60,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(100)
);

-- Zonas dentro de una obra (ej. "Piso 2 - Construcción", "Almacén").
-- Una obra tiene varias zonas; una zona puede agrupar varias cámaras.
-- code: unico por obra (no global) — dos obras distintas pueden repetir
-- "Z1" sin chocar. Mismo criterio de inmutabilidad que sites.code.
CREATE TABLE zones (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id    BIGINT       NOT NULL REFERENCES sites(id),
    code       VARCHAR(50)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100),
    UNIQUE (site_id, name),
    UNIQUE (site_id, code)
);

-- zone_id nulo = camara registrada pero sin enlazar todavia: el backend
-- no notifica su config al CV hasta que tenga site+zone+code completos
-- (ver CameraService/CLAUDE.md). code: mismo criterio de inmutabilidad
-- que sites.code/zones.code, ya global-unico desde antes de esta nota.
CREATE TABLE cameras (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id    BIGINT       NOT NULL REFERENCES sites(id),
    zone_id    BIGINT       REFERENCES zones(id),
    code       VARCHAR(50)  NOT NULL UNIQUE,
    name       VARCHAR(100),
    ip_address VARCHAR(50),
    rtsp_url   VARCHAR(300),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100)
);

CREATE TABLE workers (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id    BIGINT       NOT NULL REFERENCES sites(id),
    code       INTEGER      NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name  VARCHAR(100) NOT NULL,
    role       VARCHAR(100),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100)
);

-- Los usuarios del sistema acceden al frontend con JWT.
-- El bot de Telegram es central (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID en env vars)
-- y no se vincula a usuarios individuales en la BD.
CREATE TABLE users (
    id            BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    email         VARCHAR(200) NOT NULL UNIQUE,
    password_hash VARCHAR(300) NOT NULL,
    role_id       BIGINT       NOT NULL REFERENCES user_roles(id),
    phone         VARCHAR(20),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(100),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by    VARCHAR(100)
);

-- ────────────────────────────────────────────────────────────
-- EVENTOS (inmutables)
-- ────────────────────────────────────────────────────────────

-- external_id: UUID generado por el modulo CV, correlaciona el incidente
-- con el aviso de "clip listo" que llega minutos despues (POST separado).
CREATE TABLE incidents (
    id          BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    worker_id   BIGINT       NOT NULL REFERENCES workers(id),
    camera_id   BIGINT       NOT NULL REFERENCES cameras(id),
    site_id     BIGINT       NOT NULL REFERENCES sites(id),
    external_id VARCHAR(36)  NOT NULL UNIQUE,
    missing_epp TEXT[]       NOT NULL,
    occurred_at TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- evidence_type: 'PHOTO' | 'VIDEO'.
-- frame_b64: solo lo usa el flujo HTTP legacy (POST /api/v1/incidents).
-- storage_key/duration_seconds/file_size_bytes: solo el flujo nuevo del CV
-- con evidencia en S3 (el backend solo referencia la key para presignar).
CREATE TABLE evidence (
    id               BIGINT           PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    incident_id      BIGINT           NOT NULL REFERENCES incidents(id),
    evidence_type    VARCHAR(20)      NOT NULL,
    frame_b64        TEXT,
    storage_key      VARCHAR(500),
    duration_seconds DOUBLE PRECISION,
    file_size_bytes  BIGINT,
    created_at       TIMESTAMP        NOT NULL DEFAULT NOW()
);

CREATE TABLE notifications (
    id          BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    incident_id BIGINT    NOT NULL REFERENCES incidents(id),
    channel_id  BIGINT    NOT NULL REFERENCES notification_channels(id),
    status_id   BIGINT    NOT NULL REFERENCES notification_statuses(id),
    sent_at     TIMESTAMP,
    error_msg   TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ────────────────────────────────────────────────────────────
-- CONTACTOS POR OBRA  (reciben alertas Telegram al detectar incidente)
-- phone            : número de contacto general
-- telegram_chat_id : ID de chat del bot; se obtiene cuando la persona
--                    vincula su cuenta (ver telegram_link_code abajo) o,
--                    si ya lo conoce, lo pega directo al crear/editar el
--                    contacto. Si es NULL, no recibe alertas Telegram.
-- telegram_link_code : código corto (6 dígitos) generado al crear el
--                    contacto si no vino telegram_chat_id — el supervisor
--                    le manda "/start <código>" (un solo mensaje) a
--                    @safevision_epp_bot; TelegramLinkingPoller lo matchea
--                    y setea telegram_chat_id, dejando este campo en NULL.
--                    Sin expiración por tiempo (alcance de esta fase).
-- Fallback global: si la obra no tiene contactos con telegram_chat_id,
--                  se usa TELEGRAM_CHAT_ID del entorno (env var).
-- ────────────────────────────────────────────────────────────

CREATE TABLE site_contacts (
    id                  BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id             BIGINT       NOT NULL REFERENCES sites(id),
    name                VARCHAR(100) NOT NULL,
    phone               VARCHAR(20)  NOT NULL,
    telegram_chat_id    VARCHAR(100),
    telegram_link_code  VARCHAR(10),
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(100)
);

-- ────────────────────────────────────────────────────────────
-- CATÁLOGO EPP  (tipos disponibles en el sistema)
-- ────────────────────────────────────────────────────────────

CREATE TABLE epp_parameters (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code       VARCHAR(50)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100)
);

-- ────────────────────────────────────────────────────────────
-- CONFIGURACIÓN EPP POR OBRA
-- Relaciona qué EPPs son obligatorios en cada obra.
-- Si una obra no tiene filas aquí, el sistema devuelve todos
-- los EPPs activos del catálogo como fallback (fail-safe).
-- ────────────────────────────────────────────────────────────

CREATE TABLE site_epp_requirements (
    id               BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id          BIGINT    NOT NULL REFERENCES sites(id),
    epp_parameter_id BIGINT    NOT NULL REFERENCES epp_parameters(id),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(100),
    UNIQUE (site_id, epp_parameter_id)
);

-- Control de versión optimista para la configuración EPP de una obra (CP18,
-- HU04) — evita que dos PUT concurrentes sobre la misma obra se pisen entre
-- sí sin darse cuenta. Se crea perezosamente (lazily) en el primer PUT.
CREATE TABLE site_epp_config_versions (
    site_id BIGINT NOT NULL PRIMARY KEY REFERENCES sites(id),
    version BIGINT NOT NULL DEFAULT 0
);

-- ────────────────────────────────────────────────────────────
-- ÍNDICES
-- ────────────────────────────────────────────────────────────

CREATE INDEX idx_zones_site_id      ON zones(site_id);
CREATE INDEX idx_cameras_site_id    ON cameras(site_id);
CREATE INDEX idx_cameras_zone_id    ON cameras(zone_id);
CREATE INDEX idx_workers_site_id    ON workers(site_id);
CREATE INDEX idx_workers_code       ON workers(code);
CREATE INDEX idx_cameras_code       ON cameras(code);
CREATE INDEX idx_incidents_site_id  ON incidents(site_id);
CREATE INDEX idx_incidents_worker   ON incidents(worker_id);
CREATE INDEX idx_incidents_camera   ON incidents(camera_id);
CREATE INDEX idx_incidents_occurred ON incidents(occurred_at DESC);
CREATE INDEX idx_evidence_incident  ON evidence(incident_id);
CREATE INDEX idx_notif_incident     ON notifications(incident_id);
CREATE INDEX idx_notif_status       ON notifications(status_id);
CREATE INDEX idx_site_contacts_site ON site_contacts(site_id);
CREATE INDEX idx_site_epp_site      ON site_epp_requirements(site_id);

-- ────────────────────────────────────────────────────────────
-- SEED INICIAL (ver V2__seed_catalogs.sql)
-- ────────────────────────────────────────────────────────────
-- user_roles:              ADMIN, SUPERVISOR
-- notification_channels:   TELEGRAM
-- notification_statuses:   PENDING, SENT, FAILED
-- epp_parameters:          casco, chaleco, guantes
-- site_epp_requirements:   (vacío — se configura por obra desde el frontend)
