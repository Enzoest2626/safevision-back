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

CREATE TABLE sites (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    location   VARCHAR(200),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100)
);

-- Zonas dentro de una obra (ej. "Piso 2 - Construcción", "Almacén").
-- Una obra tiene varias zonas; una zona puede agrupar varias cámaras.
CREATE TABLE zones (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id    BIGINT       NOT NULL REFERENCES sites(id),
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100),
    UNIQUE (site_id, name)
);

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

CREATE TABLE incidents (
    id          BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    worker_id   BIGINT    NOT NULL REFERENCES workers(id),
    camera_id   BIGINT    NOT NULL REFERENCES cameras(id),
    site_id     BIGINT    NOT NULL REFERENCES sites(id),
    missing_epp TEXT[]    NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE evidence (
    id          BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    incident_id BIGINT    NOT NULL REFERENCES incidents(id),
    frame_b64   TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
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
-- phone          : número de contacto general
-- telegram_chat_id : ID de chat del bot; se obtiene cuando la persona
--                    inicia conversación con el bot (@SafeVisionBot /start).
--                    Si es NULL, no recibe alertas Telegram.
-- Fallback global: si la obra no tiene contactos con telegram_chat_id,
--                  se usa TELEGRAM_CHAT_ID del entorno (env var).
-- ────────────────────────────────────────────────────────────

CREATE TABLE site_contacts (
    id               BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id          BIGINT       NOT NULL REFERENCES sites(id),
    name             VARCHAR(100) NOT NULL,
    phone            VARCHAR(20)  NOT NULL,
    telegram_chat_id VARCHAR(100),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(100)
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
-- notification_channels:   TELEGRAM, HIKVISION
-- notification_statuses:   PENDING, SENT, FAILED
-- epp_parameters:          casco, chaleco, guantes
-- site_epp_requirements:   (vacío — se configura por obra desde el frontend)
