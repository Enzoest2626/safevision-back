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
| Reactor              | Único stack reactivo — RxJava evaluado y descartado, ver nota abajo |
| R2DBC                | Driver reactivo PostgreSQL (`ReactiveCrudRepository`)     |
| PostgreSQL           | Base de datos principal (AWS RDS en producción)          |
| SpringDoc OpenAPI    | Swagger UI — `/swagger-ui.html`                          |
| Telegram Bot API     | Notificaciones push al supervisor                        |
| Hikvision ISAPI      | Activación de sirena física en cámara (config lista, cliente HTTP pendiente) |
| Maven                | Build tool                                               |
| JaCoCo               | Cobertura de tests (`mvn test` genera `target/site/jacoco/`) |
| Checkstyle / SpotBugs | Análisis estático (`failOnViolation=false`, no bloquean el build) |
| Spotless              | Formateo automático (`spotless-maven-plugin`) — orden de imports, indentación, fin de línea; reglas livianas, sin preset Google |
| Testcontainers       | Dependencia agregada — aún sin tests de integración que la usen |

> **Nota:** este archivo documentaba originalmente una arquitectura hexagonal
> con RxJava en la capa de servicio. Esa capa nunca se implementó — el código
> real usa Reactor (`Mono`/`Flux`) de punta a punta, sin `RxJava3Adapter` ni
> conversión alguna. La sección de abajo refleja el código tal como existe.

---

## Arquitectura

Capas planas (controller → service → repository), **no hexagonal**. Todo el
pipeline reactivo usa Reactor puro (`Mono`/`Flux`), sin RxJava.

```
src/main/java/com/safevision/back/
├── controller/          ← REST controllers WebFlux (Mono/Flux)
│   ├── SiteController
│   ├── ZoneController            (anidado bajo /sites/{siteId}/zones)
│   ├── SiteContactController     (anidado bajo /sites/{siteId}/contacts)
│   ├── CameraController
│   ├── WorkerController
│   ├── UserController
│   ├── IncidentController        ← HU10, protegido con Bearer token
│   └── EppParameterController    ← HU04, reglas por obra (siteId)
├── dto/                 ← Request/Response records (validación jakarta.validation)
├── model/                ← Entidades R2DBC (records Java 21, @Table/@Id)
│   ├── Site, Zone, Camera, Worker, User, UserRole
│   ├── SiteContact, SiteEppRequirement, EppParameter
│   └── Incident, Evidence, Notification, NotificationChannel, NotificationStatus
├── repository/           ← ReactiveCrudRepository<T, Long> (R2DBC)
├── service/              ← Lógica de negocio (Reactor puro)
│   ├── SiteService, CameraService, WorkerService, UserService
│   ├── ZoneService, SiteContactService, EppParameterService
│   ├── TelegramNotificationService   ← llamada HTTP al Bot API (WebClient inyectable para tests)
│   ├── IncidentNotificationService   ← resuelve contactos de la obra + envía Telegram + registra en `notifications`
│   └── IncidentService               ← orquesta: persiste incidente + evidencia, delega notificación a IncidentNotificationService
└── config/
    ├── OpenApiConfig        ← SpringDoc / Swagger
    ├── SecurityConfig       ← WebFilter que exige Bearer en POST /api/v1/incidents
    ├── TelegramProperties
    └── HikvisionProperties  ← solo config; sin cliente HTTP aún (HU09 pendiente)
```

**IDs:** `Long` (BIGINT `GENERATED ALWAYS AS IDENTITY`), no UUID.
**Reactive first:** controllers y services retornan `Mono<T>`/`Flux<T>`, cero `.block()`.

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
| GET    | `/api/v1/sites/{siteId}/zones`             | Listar zonas de una obra             |
| POST   | `/api/v1/sites/{siteId}/zones`             | Crear zona                           |
| PUT    | `/api/v1/sites/{siteId}/zones/{zoneId}`    | Actualizar zona                      |
| DELETE | `/api/v1/sites/{siteId}/zones/{zoneId}`    | Desactivar zona                      |
| GET    | `/api/v1/sites/{siteId}/contacts`          | Listar contactos de la obra (alertas Telegram) |
| POST   | `/api/v1/sites/{siteId}/contacts`          | Crear contacto                       |
| PUT    | `/api/v1/sites/{siteId}/contacts/{contactId}` | Actualizar contacto                |
| DELETE | `/api/v1/sites/{siteId}/contacts/{contactId}` | Desactivar contacto                |
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
|--------|---------------------------|-------|---------------------------------------------------|
| POST   | `/api/v1/incidents`       | HU10  | Registra incidente EPP — único endpoint con Bearer token (`ALERT_SERVICE_TOKEN`) |
| GET    | `/api/v1/incidents`       | HU10  | Lista incidentes (filtros opcionales: `siteId`, `workerId`, `from`, `to`) |
| GET    | `/api/v1/incidents/{id}`  | HU10  | Detalle de incidente                             |

### Parámetros EPP

| Método | Endpoint                          | HU    | Descripción                                       |
|--------|------------------------------------|-------|----------------------------------------------------|
| GET    | `/api/v1/parameters/{siteId}`      | HU04  | Reglas EPP activas de una obra (fallback al catálogo completo si no tiene config propia) |
| PUT    | `/api/v1/parameters/{siteId}`      | HU04  | Actualizar reglas EPP obligatorias de esa obra     |

### Documentación

- Swagger UI: `GET /swagger-ui.html`
- OpenAPI 3 spec: `GET /v3/api-docs`

### Pendiente (documentado en las HU pero sin controller aún)

- Reportes/estadísticas (`/api/v1/reports/...`) — HU12, no implementado.
- Historial de notificaciones (`/api/v1/notifications`) — no implementado; las
  notificaciones se persisten en la tabla `notifications` pero no hay endpoint
  de lectura todavía.

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
IDs `BIGINT GENERATED ALWAYS AS IDENTITY` (no UUID). Fuente única de verdad:
[`docs/init-schema.sql`](docs/init-schema.sql) — este bloque es un resumen,
ver el archivo para el DDL completo (índices, comentarios de cada tabla).

**Catálogos de referencia** (`user_roles`, `notification_channels`,
`notification_statuses`) reemplazan los `VARCHAR` inline que este documento
describía antes — `users.role_id`, `notifications.channel_id` y
`notifications.status_id` son FKs a esos catálogos.

**Campos de auditoría** — presentes en tablas de datos maestros (`sites`, `zones`, `cameras`, `workers`, `users`, `site_contacts`, `epp_parameters`):
- `created_at` / `updated_at` — marcas de tiempo automáticas.
- `created_by` / `updated_by` — username del usuario que realizó la operación.

Las tablas de eventos (`incidents`, `evidence`, `notifications`) son inmutables: solo tienen `created_at`.

```sql
-- Catálogos
CREATE TABLE user_roles (
    id   BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code VARCHAR(50)  NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);
CREATE TABLE notification_channels (id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY, code VARCHAR(50) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL);
CREATE TABLE notification_statuses  (id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY, code VARCHAR(50) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL);

-- Obras / construction sites
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

-- Zonas dentro de una obra (ej. "Piso 2", "Almacén") — agrupan cámaras
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

-- Cámaras IP Hikvision
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

-- Trabajadores en obra
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

-- Usuarios del sistema (acceden al frontend; el bot de Telegram NO se
-- vincula a usuarios individuales — ver site_contacts más abajo)
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

-- Incidentes de incumplimiento EPP (inmutable)
CREATE TABLE incidents (
    id          BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    worker_id   BIGINT    NOT NULL REFERENCES workers(id),
    camera_id   BIGINT    NOT NULL REFERENCES cameras(id),
    site_id     BIGINT    NOT NULL REFERENCES sites(id),
    missing_epp TEXT[]    NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Evidencias visuales / frames (inmutable)
CREATE TABLE evidence (
    id          BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    incident_id BIGINT    NOT NULL REFERENCES incidents(id),
    frame_b64   TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Historial de notificaciones (inmutable)
CREATE TABLE notifications (
    id          BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    incident_id BIGINT    NOT NULL REFERENCES incidents(id),
    channel_id  BIGINT    NOT NULL REFERENCES notification_channels(id),
    status_id   BIGINT    NOT NULL REFERENCES notification_statuses(id),
    sent_at     TIMESTAMP,
    error_msg   TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Contactos por obra — reciben alertas Telegram al detectarse un incidente.
-- Fallback: si la obra no tiene contactos con telegram_chat_id, se usa
-- TELEGRAM_CHAT_ID del entorno.
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

-- Catálogo EPP (tipos disponibles en el sistema)
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

-- Qué EPP son obligatorios por obra. Sin filas para una obra => el sistema
-- devuelve todos los EPP activos del catálogo como fallback (fail-safe).
CREATE TABLE site_epp_requirements (
    id               BIGINT    PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id          BIGINT    NOT NULL REFERENCES sites(id),
    epp_parameter_id BIGINT    NOT NULL REFERENCES epp_parameters(id),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(100),
    UNIQUE (site_id, epp_parameter_id)
);
```

**Seed inicial:**
- `user_roles`: `ADMIN`, `SUPERVISOR`
- `notification_channels`: `TELEGRAM`, `HIKVISION`
- `notification_statuses`: `PENDING`, `SENT`, `FAILED`
- `epp_parameters`: `casco`, `chaleco`, `guantes`
- `site_epp_requirements`: vacío — se configura por obra desde el frontend

---

## Flujo de un Incidente

```
CV Module
   │
   └─ POST /api/v1/incidents  (Bearer: ALERT_SERVICE_TOKEN)
         │
         ├─ 1. Validar Bearer token (WebFilter en SecurityConfig)
         ├─ 2. Resolver worker_code + camera_code + site_name → IDs (JOIN a BD)
         ├─ 3. Persistir en `incidents`
         ├─ 4. Persistir frame en `evidence`
         ├─ 5. Resolver contactos de la obra (site_contacts, fallback a TELEGRAM_CHAT_ID)
         ├─ 6. TelegramNotificationService → Telegram Bot API (por cada contacto)
         └─ 7. Persistir en `notifications` (SENT / FAILED por contacto)
```

> Hikvision ISAPI (activación de sirena, HU09) aún no está integrado en este
> flujo — solo existe `HikvisionProperties` como configuración base.

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
| Aplicación   | AWS EC2 (Docker)      | Imagen Docker del backend publicada en ECR, pull automático vía UserData (ya no JAR+Corretto) |
| Secretos     | AWS Secrets Manager   | Tokens, credenciales de BD            |
| CI/CD        | GitHub Actions        | Build → Test → Deploy                  |

---

## Convenciones de Código

- **Java 21** — records para DTOs/domain, sealed classes y pattern matching.
- **Nombres en inglés** — código, tablas, columnas, variables, métodos.
- **Comentarios y Javadoc en español.**
- **Reactive first** — cero `.block()` en el flujo principal. Reactor puro (`Mono`/`Flux`), sin RxJava.
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
- Cobertura mínima: **70%** en `src/main/` — verificado con JaCoCo (`mvn test`, reporte en `target/site/jacoco/`).
- **Estado verificado (2026-07-19):** 122 tests, 0 fallos, 0 errores, 1 omitido — cobertura de instrucciones 94.6%.
- Tests actuales son unitarios (controller con `WebTestClient` + Mockito, service con StepVerifier/Mockito).
- `Testcontainers` está en `pom.xml` pero **aún no hay tests de integración que lo usen** — pendiente antes de cerrar esa parte del checklist.
- Mocks para Telegram en tests unitarios (`TelegramNotificationServiceTest`, `TelegramNotificationPerformanceTest`) — `TelegramNotificationService` recibe `WebClient.Builder` inyectado en vez de construir su propio `WebClient`, para poder mockear el `ExchangeFunction` sin llamadas HTTP reales (Hikvision aún no tiene cliente que mockear).

---

## Relación con Otros Módulos

| Dirección          | Endpoint / Canal           | Qué hace                              |
|--------------------|----------------------------|---------------------------------------|
| CV → Backend       | `POST /api/v1/incidents`   | Envía incidente EPP con imagen        |
| CV → Backend       | `GET /api/v1/parameters/{siteId}` | Consulta reglas EPP activas de la obra |
| Frontend → Backend | Todos los endpoints REST   | Gestión de datos maestros (reportes aún no implementados) |
| Backend → Telegram | Telegram Bot API           | Alerta push a los contactos de la obra |
| Backend → Hikvision| ISAPI HTTP (pendiente)     | Activará sirena física en cámara (HU09, no implementado) |
| Backend → AWS RDS  | R2DBC                      | Persiste todos los eventos            |

---

## Estado Actual

- [x] Proyecto Spring Boot 3 inicializado con dependencias completas
- [x] Esquema DB creado (`docs/init-schema.sql`) — sin Flyway migrations todavía (script plano, no versionado por migration tool)
- [x] Entidades de dominio definidas (records Java 21) — `Site`, `Zone`, `Camera`, `Worker`, `User`, `UserRole`, `SiteContact`, `EppParameter`, `SiteEppRequirement`, `Incident`, `Evidence`, `Notification`, `NotificationChannel`, `NotificationStatus`
- [x] CRUD Sites implementado
- [x] CRUD Cameras implementado
- [x] CRUD Workers implementado
- [x] CRUD Users implementado
- [x] CRUD Zones implementado (anidado bajo obra)
- [x] CRUD Site Contacts implementado (anidado bajo obra)
- [x] Endpoint POST /api/v1/incidents (HU10)
- [x] Endpoint GET /api/v1/incidents (HU10)
- [x] Endpoint GET/PUT /api/v1/parameters/{siteId} (HU04) — por obra, con fallback al catálogo global
- [ ] Reportes y estadísticas implementados (HU12)
- [x] Servicio Telegram Bot (`TelegramNotificationService`) — notifica a los contactos de la obra
- [x] Notificación de incidentes extraída a `IncidentNotificationService` — mantiene `IncidentService` bajo el límite de 150 líneas
- [ ] Cliente Hikvision ISAPI (HU09) — solo existe `HikvisionProperties` (config), sin llamada HTTP real
- [x] Swagger/OpenAPI configurado (`OpenApiConfig`)
- [x] Seguridad Bearer token — solo protege `POST /api/v1/incidents`; el resto de endpoints está abierto (sin auth de usuario/JWT todavía)
- [x] Tests unitarios — 122 tests, 0 fallos, 1 omitido, cobertura de instrucciones 94.6% (JaCoCo, verificado 2026-07-19)
- [ ] Tests de integración Testcontainers — dependencia agregada, sin tests que la usen aún
- [x] Infraestructura como código (CloudFormation) — RDS + 2 EC2 (backend, CV), bootstrap 100% automático vía UserData, pull de imágenes Docker desde ECR (`infra/`); pensada para crearse/borrarse por sesión de demo
- [x] Checkstyle + SpotBugs + Spotless integrados al build (no bloquean, `failOnViolation=false`)
