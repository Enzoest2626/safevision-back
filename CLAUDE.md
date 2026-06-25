# CLAUDE.md — SafeVision Backend

> Archivo de contexto para Claude Code.
> **Leer ANTES de cualquier intervención en el código.**

---

## Descripción del Proyecto

Backend principal del sistema SafeVision: plataforma de monitoreo de EPP
(Equipos de Protección Personal) en obras de construcción.

El servicio recibe eventos de incumplimiento desde el módulo de Computer Vision
(Python/YOLOv11), gestiona los datos maestros de la operación (obras, cámaras,
trabajadores, usuarios), genera reportes, envía notificaciones vía Telegram y
activa sirenas físicas vía Hikvision ISAPI.

Tesis de egreso — Universidad Peruana de Ciencias Aplicadas (UPC)
Programa: Ingeniería de Sistemas
Autores: Enzo Esteban Quispe / Yvette Flores Castillo

---

## Reglas no negociables para Claude Code

### Identidad en commits

- **Autor único:** `user.name = "Enzo Esteban"` / `user.email = "u202417985@upc.edu.pe"`
- **PROHIBIDO** en cualquier commit:
  - `Co-authored-by: Claude <...>`
  - `Generated with Claude Code`
  - Cualquier firma, footer, emoji decorativo o mención a IA.

### Operaciones Git prohibidas sin confirmación explícita

Claude **NUNCA** ejecuta automáticamente:
- `git push` de ningún tipo
- `gh pr create` o apertura de Pull Request
- `git merge` hacia `release` o `main`
- `git reset --hard` sobre commits ya pusheados
- Borrado de ramas remotas

### Operaciones de sistema prohibidas

- Nunca hardcodear tokens, contraseñas ni credenciales — todo en variables de entorno.
- Nunca commitear `application-prd.yml` con valores reales.
- Nunca exponer el bot token de Telegram ni credenciales de Hikvision en el código.

### Flujo esperado de Claude

1. Leer este archivo + el archivo a modificar.
2. Explicar el cambio propuesto.
3. Aplicar el cambio.
4. Verificar: `./mvnw verify -q`
5. Sugerir el mensaje de commit — no commitear hasta aprobación.
6. Detenerse. No pushear. No abrir PR.

---

## Stack Técnico

| Componente           | Versión / Detalle                                         |
|----------------------|-----------------------------------------------------------|
| Java                 | 21 (LTS)                                                  |
| Spring Boot          | 3.x                                                       |
| Spring WebFlux       | Capa web reactiva (Mono / Flux)                          |
| RxJava               | 3.x — lógica de negocio en capa de servicio             |
| R2DBC                | Driver reactivo PostgreSQL                               |
| PostgreSQL           | Base de datos principal (AWS RDS en producción)          |
| SpringDoc OpenAPI    | Swagger UI — `/swagger-ui.html`                          |
| Telegram Bot API     | Notificaciones push al supervisor                        |
| Hikvision ISAPI      | Activación de sirena física en cámara                    |
| Maven                | Build tool                                               |
| Testcontainers       | Tests de integración con PostgreSQL real                 |

---

## Arquitectura

Arquitectura **Hexagonal (Ports & Adapters)** con capas reactivas.

```
src/main/java/com/safevision/back/
├── adapter/
│   ├── in/
│   │   └── web/                    ← Controllers WebFlux
│   │       ├── SiteController
│   │       ├── CameraController
│   │       ├── WorkerController
│   │       ├── UserController
│   │       ├── IncidentController
│   │       ├── ReportController
│   │       ├── NotificationController
│   │       └── EppParameterController
│   └── out/
│       ├── persistence/            ← Repositorios R2DBC
│       │   ├── SiteR2dbcRepository
│       │   ├── CameraR2dbcRepository
│       │   ├── WorkerR2dbcRepository
│       │   ├── UserR2dbcRepository
│       │   ├── IncidentR2dbcRepository
│       │   ├── EvidenceR2dbcRepository
│       │   ├── NotificationR2dbcRepository
│       │   └── EppParameterR2dbcRepository
│       ├── telegram/               ← Telegram Bot adapter
│       │   └── TelegramNotificationAdapter
│       └── hikvision/              ← Hikvision ISAPI adapter
│           └── HikvisionSirenAdapter
├── application/
│   └── service/                    ← Casos de uso con RxJava
│       ├── SiteService
│       ├── CameraService
│       ├── WorkerService
│       ├── UserService
│       ├── IncidentService         ← Orquesta: persiste + notifica
│       ├── ReportService
│       ├── NotificationService
│       └── EppParameterService
├── domain/
│   ├── model/                      ← Entidades del dominio (records Java 21)
│   │   ├── Site
│   │   ├── Camera
│   │   ├── Worker
│   │   ├── User
│   │   ├── Incident
│   │   ├── Evidence
│   │   ├── Notification
│   │   └── EppParameter
│   └── port/
│       ├── in/                     ← Interfaces de casos de uso
│       │   ├── ManageSiteUseCase
│       │   ├── ManageCameraUseCase
│       │   ├── ManageWorkerUseCase
│       │   ├── ManageUserUseCase
│       │   ├── RegisterIncidentUseCase
│       │   ├── GenerateReportUseCase
│       │   ├── ManageNotificationUseCase
│       │   └── ManageEppParameterUseCase
│       └── out/                    ← Interfaces de repositorios/servicios externos
│           ├── SiteRepositoryPort
│           ├── CameraRepositoryPort
│           ├── WorkerRepositoryPort
│           ├── UserRepositoryPort
│           ├── IncidentRepositoryPort
│           ├── EvidenceRepositoryPort
│           ├── NotificationRepositoryPort
│           ├── EppParameterRepositoryPort
│           ├── AlertNotificationPort   ← Telegram
│           └── SirenActivationPort     ← Hikvision
└── config/
    ├── OpenApiConfig               ← SpringDoc / Swagger
    ├── SecurityConfig              ← Bearer token filter
    ├── R2dbcConfig
    ├── TelegramConfig
    └── HikvisionConfig
```

**Regla de dependencias:** el dominio no conoce adapters ni Spring.
Los adapters dependen del dominio, nunca al revés.

**Mezcla WebFlux + RxJava:**
- Controllers retornan `Mono<T>` / `Flux<T>` (Reactor — requerido por WebFlux).
- Services usan `Single<T>` / `Observable<T>` / `Completable` (RxJava 3).
- Conversión siempre con `RxJava3Adapter`. **Nunca `.block()`.**

---

## API Endpoints

### Gestión de Datos Maestros

| Método | Endpoint               | Descripción                          |
|--------|------------------------|--------------------------------------|
| GET    | `/api/v1/sites`        | Listar obras activas                 |
| POST   | `/api/v1/sites`        | Crear obra                           |
| GET    | `/api/v1/sites/{id}`   | Detalle de obra                      |
| PUT    | `/api/v1/sites/{id}`   | Actualizar obra                      |
| DELETE | `/api/v1/sites/{id}`   | Desactivar obra (soft delete)        |
| GET    | `/api/v1/cameras`      | Listar cámaras                       |
| POST   | `/api/v1/cameras`      | Crear cámara                         |
| GET    | `/api/v1/cameras/{id}` | Detalle de cámara                    |
| PUT    | `/api/v1/cameras/{id}` | Actualizar cámara                    |
| DELETE | `/api/v1/cameras/{id}` | Desactivar cámara                    |
| GET    | `/api/v1/workers`      | Listar trabajadores                  |
| POST   | `/api/v1/workers`      | Crear trabajador                     |
| GET    | `/api/v1/workers/{id}` | Detalle de trabajador                |
| PUT    | `/api/v1/workers/{id}` | Actualizar trabajador                |
| DELETE | `/api/v1/workers/{id}` | Desactivar trabajador                |
| GET    | `/api/v1/users`        | Listar usuarios del sistema          |
| POST   | `/api/v1/users`        | Crear usuario                        |
| GET    | `/api/v1/users/{id}`   | Detalle de usuario                   |
| PUT    | `/api/v1/users/{id}`   | Actualizar usuario                   |
| DELETE | `/api/v1/users/{id}`   | Desactivar usuario                   |

### Incidentes EPP (desde módulo CV)

| Método | Endpoint                  | HU    | Descripción                                      |
|--------|---------------------------|-------|--------------------------------------------------|
| POST   | `/api/v1/incidents`       | HU10  | Registra incidente EPP (desde módulo CV)         |
| GET    | `/api/v1/incidents`       | HU10  | Lista incidentes (filtros: site, worker, fecha)  |
| GET    | `/api/v1/incidents/{id}`  | HU10  | Detalle de incidente + evidencia                 |

### Reportes y Estadísticas

| Método | Endpoint                         | Descripción                               |
|--------|----------------------------------|-------------------------------------------|
| GET    | `/api/v1/reports/incidents`      | Reporte de incidentes por rango de fecha  |
| GET    | `/api/v1/reports/summary`        | Totales y estadísticas para dashboard     |

### Notificaciones

| Método | Endpoint                    | Descripción                         |
|--------|-----------------------------|-------------------------------------|
| GET    | `/api/v1/notifications`     | Historial de notificaciones enviadas|

### Parámetros EPP

| Método | Endpoint              | HU    | Descripción                             |
|--------|-----------------------|-------|-----------------------------------------|
| GET    | `/api/v1/parameters`  | HU04  | Reglas EPP activas (usado por CV)       |
| PUT    | `/api/v1/parameters`  | HU04  | Actualizar reglas EPP                   |

### Documentación

- Swagger UI: `GET /swagger-ui.html`
- OpenAPI 3 spec: `GET /v3/api-docs`

---

### Payload POST /api/v1/incidents (desde Python CV)

```json
{
  "worker_code": 3,
  "missing_epp": ["helmet", "vest"],
  "timestamp": "2026-06-24T13:30:00",
  "camera_code": "CAM-01",
  "site_name": "Main-Site",
  "frame_b64": "<imagen JPEG en base64>"
}
```

---

## Esquema de Base de Datos

Todo en inglés. Soft delete vía columna `active`. FK entre tablas.

**Campos de auditoría** — presentes en tablas de datos maestros (`sites`, `cameras`, `workers`, `users`):
- `created_at` / `updated_at` — marcas de tiempo automáticas.
- `created_by` / `updated_by` — username del usuario que realizó la operación.

Las tablas de eventos (`incidents`, `evidence`, `notifications`) son inmutables: solo tienen `created_at`.

```sql
-- Obras / construction sites
CREATE TABLE sites (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    location   VARCHAR(200),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100)
);

-- Cámaras IP Hikvision
CREATE TABLE cameras (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id    UUID         NOT NULL REFERENCES sites(id),
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

-- Trabajadores en obra
CREATE TABLE workers (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id    UUID         NOT NULL REFERENCES sites(id),
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

-- Usuarios del sistema
CREATE TABLE users (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username         VARCHAR(100) NOT NULL UNIQUE,
    email            VARCHAR(200) NOT NULL UNIQUE,
    password_hash    VARCHAR(300) NOT NULL,
    role             VARCHAR(50)  NOT NULL DEFAULT 'SUPERVISOR',
    telegram_chat_id VARCHAR(100),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(100)
);

-- Incidentes de incumplimiento EPP (inmutable)
CREATE TABLE incidents (
    id          UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    worker_id   UUID      NOT NULL REFERENCES workers(id),
    camera_id   UUID      NOT NULL REFERENCES cameras(id),
    site_id     UUID      NOT NULL REFERENCES sites(id),
    missing_epp TEXT[]    NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Evidencias visuales / frames (inmutable)
CREATE TABLE evidence (
    id          UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID      NOT NULL REFERENCES incidents(id),
    frame_b64   TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Historial de notificaciones (inmutable)
CREATE TABLE notifications (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID         NOT NULL REFERENCES incidents(id),
    channel     VARCHAR(50)  NOT NULL,
    status      VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    sent_at     TIMESTAMP,
    error_msg   TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Reglas EPP configurables (global, sin FK a site)
CREATE TABLE epp_parameters (
    id           UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    required_epp TEXT[]    NOT NULL DEFAULT '{helmet,vest,gloves}',
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(100)
);
```

**Enumerados esperados:**
- `channel`: `TELEGRAM`, `HIKVISION`
- `status`: `PENDING`, `SENT`, `FAILED`
- `role` (users): `ADMIN`, `SUPERVISOR`

---

## Flujo de un Incidente

```
CV Module
   │
   └─ POST /api/v1/incidents  (Bearer: ALERT_SERVICE_TOKEN)
         │
         ├─ 1. Validar Bearer token
         ├─ 2. Resolver worker_code + camera_code → UUIDs (JOIN a BD)
         ├─ 3. Persistir en `incidents`
         ├─ 4. Persistir frame en `evidence`
         ├─ 5. TelegramNotificationAdapter → Telegram Bot API
         ├─ 6. HikvisionSirenAdapter → Hikvision ISAPI
         └─ 7. Persistir en `notifications` (SENT / FAILED por canal)
```

---

## Variables de Entorno

```env
# Base de datos (AWS RDS en PRD)
SPRING_R2DBC_URL=r2dbc:postgresql://localhost:5432/safevision
SPRING_R2DBC_USERNAME=safevision
SPRING_R2DBC_PASSWORD=

# Telegram Bot
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=

# Hikvision ISAPI
HIKVISION_BASE_URL=
HIKVISION_USERNAME=
HIKVISION_PASSWORD=

# Seguridad
ALERT_SERVICE_TOKEN=    # Bearer token que valida el módulo CV

# Servidor
SERVER_PORT=8080
```

Usar perfiles: `application-dev.yml`, `application-prd.yml`.
**Nunca** commitear `application-prd.yml` con valores reales.

---

## Infraestructura AWS

| Componente   | Servicio AWS          | Detalle                                 |
|--------------|-----------------------|-----------------------------------------|
| Base de datos | AWS RDS              | PostgreSQL 16, Multi-AZ en PRD         |
| Aplicación   | AWS EC2 / ECS Fargate | Spring Boot JAR / contenedor Docker   |
| Secretos     | AWS Secrets Manager   | Tokens, credenciales de BD            |
| CI/CD        | GitHub Actions        | Build → Test → Deploy                  |

---

## Convenciones de Código

- **Java 21** — records para DTOs/domain, sealed classes y pattern matching.
- **Nombres en inglés** — código, tablas, columnas, variables, métodos.
- **Comentarios y Javadoc en español.**
- **Reactive first** — cero `.block()` en el flujo principal.
- Conversión WebFlux↔RxJava siempre con `RxJava3Adapter`.
- Un archivo = una clase pública.
- Clases < 150 líneas salvo justificación.
- Todos los endpoints anotados con `@Operation` y `@ApiResponse` de SpringDoc.

---

## Convenciones de Git

```
main     →  PRD
release  →  QA
develop  →  DEV
feature/HU<NN>-<short-name>
```

Commits convencionales en español:
```
feat(HU10): implementa registro de incidente y notificación Telegram
fix(HU04): corrige validación de reglas EPP vacías
feat(sites): implementa CRUD de obras
feat(setup): inicializa proyecto con dependencias y configuración base
```

---

## Testing

- Framework: JUnit 5 + Mockito + StepVerifier (reactivos).
- Cobertura mínima: **70%** en `src/main/`.
- Tests de integración con Testcontainers (PostgreSQL real, no H2).
- Mocks para Telegram y Hikvision en tests unitarios.

---

## Relación con Otros Módulos

| Dirección          | Endpoint / Canal           | Qué hace                              |
|--------------------|----------------------------|---------------------------------------|
| CV → Backend       | `POST /api/v1/incidents`   | Envía incidente EPP con imagen        |
| CV → Backend       | `GET /api/v1/parameters`   | Consulta reglas EPP activas           |
| Frontend → Backend | Todos los endpoints REST   | Gestión de datos maestros y reportes  |
| Backend → Telegram | Telegram Bot API           | Alerta push al supervisor             |
| Backend → Hikvision| ISAPI HTTP                 | Activa sirena física en cámara        |
| Backend → AWS RDS  | R2DBC                      | Persiste todos los eventos            |

---

## Estado Actual

- [ ] Proyecto Spring Boot 3 inicializado con dependencias completas
- [ ] Esquema DB creado (script SQL + Flyway migrations)
- [ ] Entidades de dominio definidas (records Java 21)
- [ ] Ports de entrada y salida definidos
- [ ] CRUD Sites implementado
- [ ] CRUD Cameras implementado
- [ ] CRUD Workers implementado
- [ ] CRUD Users implementado
- [ ] Endpoint POST /api/v1/incidents (HU10)
- [ ] Endpoint GET /api/v1/incidents (HU10)
- [ ] Endpoint GET/PUT /api/v1/parameters (HU04)
- [ ] Reportes y estadísticas implementados
- [ ] Adapter Telegram Bot
- [ ] Adapter Hikvision ISAPI
- [ ] Swagger/OpenAPI configurado
- [ ] Seguridad Bearer token
- [ ] Tests unitarios (cobertura >= 70%)
- [ ] Tests de integración Testcontainers
- [ ] Configuración AWS RDS / deploy
