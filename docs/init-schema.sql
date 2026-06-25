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

CREATE TABLE cameras (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id    BIGINT       NOT NULL REFERENCES sites(id),
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

CREATE TABLE users (
    id               BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    username         VARCHAR(100) NOT NULL UNIQUE,
    email            VARCHAR(200) NOT NULL UNIQUE,
    password_hash    VARCHAR(300) NOT NULL,
    role_id          BIGINT       NOT NULL REFERENCES user_roles(id),
    telegram_chat_id VARCHAR(100),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(100)
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
-- CONFIGURACIÓN EPP  (una fila por tipo de EPP)
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
-- ÍNDICES
-- ────────────────────────────────────────────────────────────

CREATE INDEX idx_cameras_site_id    ON cameras(site_id);
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

-- ────────────────────────────────────────────────────────────
-- SEED INICIAL (ver V2__seed_catalogs.sql)
-- ────────────────────────────────────────────────────────────
-- user_roles:              ADMIN, SUPERVISOR
-- notification_channels:   TELEGRAM, HIKVISION
-- notification_statuses:   PENDING, SENT, FAILED
-- epp_parameters:          helmet, vest, gloves
