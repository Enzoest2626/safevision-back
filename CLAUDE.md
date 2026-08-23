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
guarda evidencia de cada incidente (foto + clip de video) en S3.

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
- Nunca exponer el bot token de Telegram en el código.

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
| Maven                | Build tool                                               |
| JaCoCo               | Cobertura de tests (`mvn test` genera `target/site/jacoco/`) |
| Checkstyle / SpotBugs | Análisis estático (`failOnViolation=false`, no bloquean el build) |
| Spotless              | Formateo automático (`spotless-maven-plugin`) — orden de imports, indentación, fin de línea; reglas livianas, sin preset Google |
| Testcontainers       | Dependencia agregada — aún sin tests de integración que la usen |

> **Nota histórica:** este archivo documentó durante mucho tiempo una
> arquitectura hexagonal con RxJava que nunca se implementó — el código usó
> capas planas (controller → service → repository) hasta que se adoptó
> hexagonal de verdad (ver sección siguiente). El stack reactivo sigue
> siendo Reactor puro (`Mono`/`Flux`) de punta a punta — RxJava nunca se usó,
> ni antes ni ahora.

---

## Arquitectura — Hexagonal (Ports & Adapters)

`src/` sigue Arquitectura Hexagonal en 3 capas, simétrica con el módulo CV
(Python): `domain/`, `application/`, `infrastructure/`.

**Decisión pragmática documentada:** los 14 records de `domain/model/`
mantienen sus anotaciones R2DBC (`@Table`/`@Id`) — son entidades anémicas
sin lógica de negocio, y separarlas en un modelo de dominio puro + una
entidad de persistencia + un mapper, por cada una, es código mecánico
nuevo sin beneficio real (no hay lógica que proteger de filtrarse). Misma
honestidad que la nota de arriba sobre RxJava: se documenta el trade-off
en vez de aparentar pureza que no existe.

**Decisión pragmática documentada (puertos de repositorio):** los 15
`*RepositoryPort` en `application/ports/out/` extienden
`ReactiveCrudRepository<T, Long>` en vez de redeclarar `save`/`findById`/etc.
a mano — Spring Data declara esos métodos como genéricos (`<S extends T> Mono<S> save(S)`),
y una redeclaración no genérica en el puerto choca en tiempo de compilación
("both define save(S), but with unrelated return types"). El puerto importa
un tipo de Spring Data (`ReactiveCrudRepository`), una concesión a la pureza
hexagonal estricta, pero es el patrón estándar para exponer repos Spring
Data como puertos sin duplicar toda su API a mano.

```
src/main/java/com/safevision/back/
├── domain/
│   └── model/              ← Entidades R2DBC (records Java 21, @Table/@Id)
│       ├── Site, Zone, Camera, Worker, User, UserRole
│       ├── SiteContact, SiteEppRequirement, SiteEppConfigVersion, EppParameter
│       └── Incident (+externalId), Evidence (+evidenceType/storageKey/
│           durationSeconds/fileSizeBytes), Notification, NotificationChannel,
│           NotificationStatus
├── application/
│   ├── ports/out/          ← 15 *RepositoryPort + NotificationChannelPort,
│   │                          EvidenceStoragePort, RulesPublisherPort
│   └── service/            ← Lógica de negocio (Reactor puro), depende solo de puertos
│       ├── SiteService, CameraService, WorkerService, UserService
│       ├── ZoneService, SiteContactService, EppParameterService
│       ├── IncidentNotificationService  ← resuelve contactos + notifica + registra en `notifications`
│       └── IncidentService              ← orquesta: persiste incidente+evidencia,
│                                            registerFromCv/registerClipReady (flujo MQTT)
└── infrastructure/
    ├── web/                 ← Controllers (driving) + dto/ (Request/Response)
    │   ├── SiteController, ZoneController, SiteContactController
    │   ├── CameraController, WorkerController, UserController
    │   ├── IncidentController        ← HU10 HTTP legacy + GET .../evidence
    │   └── EppParameterController    ← HU04
    ├── persistence/         ← 15 repos Spring Data, cada uno
    │                            `extends XRepositoryPort {}` (implementación via proxy)
    ├── messaging/            ← MqttClientConfig, MqttIncidentSubscriber (driving,
    │                            suscribe a incidentes/clips del CV), MqttRulesPublisher
    │                            (implementa RulesPublisherPort), dto/ (CvIncidentMessage,
    │                            CvClipReadyMessage)
    ├── notification/         ← TelegramNotificationService (implementa NotificationChannelPort)
    ├── storage/              ← EvidencePresignService (implementa EvidenceStoragePort, S3 URLs prefirmadas)
    └── config/                ← OpenApiConfig, SecurityConfig, TelegramProperties,
                                   MqttProperties, S3Properties
```

**IDs:** `Long` (BIGINT `GENERATED ALWAYS AS IDENTITY`), no UUID
(`incidents.external_id` es la excepción — un `VARCHAR(36)` UUID generado
por el CV, solo para correlación, no es la PK).
**Reactive first:** controllers y services retornan `Mono<T>`/`Flux<T>`, cero `.block()`.

---

## Formato de Respuesta — `ApiEnvelope<T>` (2026-08-15)

Toda respuesta HTTP de esta API (todos los controllers, incluido `/api/v1/auth/login`)
va envuelta en el mismo sobre — estándar acordado con el frontend:

```json
// Éxito
{"status": "200", "datetime": "2026-08-15T20:00:00Z", "error": false, "data": { ... }}

// Error
{"status": "404", "datetime": "2026-08-15T20:00:00Z", "error": true,
 "errorCode": "NOT_FOUND", "errorDescription": "Site not found"}
```

- `infrastructure/web/dto/ApiEnvelope.java` — el record genérico + los
  helpers estáticos que hacen el wrapping: `wrap(Mono<T>, status)`,
  `wrapList(Flux<T>, status)` (junta el `Flux` en una `List` antes de
  envolver — un `Flux` no puede emitir "el array completo" elemento a
  elemento y a la vez llevar `status`/`datetime`/`error` en el mismo
  objeto JSON, así que se pierde el streaming incremental del listado a
  cambio del envoltorio uniforme) y `wrapVoid(Mono<Void>, status)`.
  No se llama `ApiResponse` para no chocar con la anotación Swagger
  `io.swagger.v3.oas.annotations.responses.ApiResponse` que ya usa cada
  controller.
- Cada controller solo cambia el tipo de retorno
  (`Flux<XResponse>` → `Mono<ApiEnvelope<List<XResponse>>>`, etc.) y llama
  al helper — la lógica de negocio en `application/service/*` no se tocó
  para nada.
- `infrastructure/web/GlobalExceptionHandler.java` (`@RestControllerAdvice`)
  convierte cualquier `ResponseStatusException` (los que ya lanzan los
  services) en el sobre de error — `errorCode` = nombre del `HttpStatus`
  (ej. `"NOT_FOUND"`), `errorDescription` = el mensaje de la excepción. No
  hizo falta tocar ningún `new ResponseStatusException(status, "mensaje")`
  existente en los services.
- **DELETE pasó de `204 No Content` a `200 OK`** — un 204 no puede llevar
  body, y ahora todo endpoint lleva el `ApiEnvelope` en el body (con
  `data: null`). Si agregás un endpoint nuevo con `@DeleteMapping`, no le
  pongas `@ResponseStatus(NO_CONTENT)`.
- El HTTP status real de la respuesta (200/201/404/401/...) sigue siendo
  el mismo de siempre — el campo `"status"` del body solo lo repite para
  quien lea el JSON sin mirar la cabecera HTTP.

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

`UserResponse` incluye `roleCode` (además de `roleId`) desde 2026-08-15 —
`UserService.findAll`/`findById` lo resuelven vía `userRoleRepository`
(`create`/`update` ya tenían el rol en scope al validar `roleCode` del
request). Necesario para que el frontend pueda mostrar/filtrar por rol sin
adivinar el mapeo roleId→código. **`UserRequest.password` es obligatorio
incluso en `PUT`** — no hay forma de actualizar un usuario sin reenviar una
contraseña (no es un bug nuevo, ya era así; solo quedó más visible al
construir la pantalla de gestión de usuarios del frontend).

### Incidentes EPP

| Método | Endpoint                       | HU    | Descripción                                      |
|--------|---------------------------------|-------|---------------------------------------------------|
| POST   | `/api/v1/incidents`             | HU10  | **Legacy HTTP** — sin uso activo del CV (que ahora publica por MQTT), pero sigue funcionando con Bearer token (`ALERT_SERVICE_TOKEN`); se deja sin borrar por si hace falta volver atrás |
| GET    | `/api/v1/incidents`             | HU10  | Lista incidentes (filtros opcionales: `siteId`, `workerId`, `from`, `to`) |
| GET    | `/api/v1/incidents/{id}`        | HU10  | Detalle de incidente                             |
| GET    | `/api/v1/incidents/{id}/evidence` |     | Evidencia (foto/clip) del incidente, con URL de S3 prefirmada de corta duración |

### Parámetros EPP

| Método | Endpoint                          | HU    | Descripción                                       |
|--------|------------------------------------|-------|----------------------------------------------------|
| GET    | `/api/v1/parameters/{siteId}`      | HU04  | Reglas EPP activas de una obra (fallback al catálogo completo si no tiene config propia) |
| PUT    | `/api/v1/parameters/{siteId}`      | HU04  | Actualizar reglas EPP obligatorias de esa obra     |

### Reportes (HU12)

| Método | Endpoint            | Descripción                                       |
|--------|----------------------|-----------------------------------------------------|
| GET    | `/api/v1/reports`    | Reporte agregado de incidentes (filtros opcionales `siteId`, `from`, `to`; default últimos 30 días) |

`ReportController` → `ReportService` → `ReportRepositoryPort` (implementado
por `ReportQueryRepository`, único adaptador del repo que usa
`DatabaseClient` en vez de `ReactiveCrudRepository` — no hay una entidad
"reporte" que persistir, son proyecciones de solo lectura con `GROUP BY`
sobre `incidents`/`sites`/`zones`/`workers`/`notifications`). Devuelve:
total de incidentes, desglose por tipo de EPP faltante (`unnest(missing_epp)`),
por obra, por zona, tendencia diaria (rellenada con ceros los días sin
incidentes), top 5 trabajadores con más incidentes, tendencia vs. periodo
anterior, obra con más incidentes, y salud de notificaciones Telegram
(enviadas vs. fallidas, join contra `notification_statuses`).

**Deliberadamente sin "% de cumplimiento EPP":** el esquema solo tiene
`incidents` (violaciones); no existe una tabla de "chequeos conformes", así
que no hay denominador real con el que calcular un porcentaje de
cumplimiento — agregarlo sería inventar un número. Si en el futuro el
módulo CV empieza a reportar también frames conformes (no solo
incumplimientos), ahí recién tendría sentido esta métrica.

### Autenticación

| Método | Endpoint               | Descripción                          |
|--------|------------------------|--------------------------------------|
| POST   | `/api/v1/auth/login`   | **Público.** `{username, password}` → `{token, tokenType, expiresInSeconds, username, role}`. Valida contra `users` (BCrypt) y emite un JWT firmado con `app.security.jwt-secret` (`JWT_SECRET` env var), expiración `app.security.jwt-expiration-minutes` (`JWT_EXPIRATION_MINUTES`, default 480 = 8h). |

Todo el resto de endpoints (excepto `/api/v1/auth/**`, Swagger/OpenAPI y el
`POST /api/v1/incidents` legacy, que sigue con su propio `ALERT_SERVICE_TOKEN`)
exige `Authorization: Bearer <jwt>` — validado por un `WebFilter`
(`SecurityConfig.jwtAuthFilter`), sin `UserDetailsService`/
`ReactiveAuthenticationManager` completo, mismo patrón liviano que ya usaba
`alertTokenFilter` para el token del CV. `JwtService`
(`infrastructure/security/`) genera y valida el token; no hay endpoint de
refresh ni logout server-side (JWT stateless — el frontend simplemente
descarta el token).

**Bootstrap:** como `POST /api/v1/users` ahora también exige JWT, sin un
usuario semilla nadie podría loguearse nunca. `docs/seed-data.sql` inserta
un usuario `admin` (rol `ADMIN`) con password `SafeVision2026!` — cambiar
en cuanto exista un flujo real de gestión de usuarios.

### Documentación

- Swagger UI: `GET /swagger-ui.html`
- OpenAPI 3 spec: `GET /v3/api-docs`
- El botón "Authorize" de Swagger usa el esquema `bearerAuth` (JWT de
  `/api/v1/auth/login`), aplicado por defecto a todos los endpoints salvo
  `/api/v1/auth/login` (marcado `@SecurityRequirements` vacío).

### Pendiente (documentado en las HU pero sin controller aún)

- Historial de notificaciones (`/api/v1/notifications`) — no implementado; las
  notificaciones se persisten en la tabla `notifications` pero no hay endpoint
  de lectura todavía (sí se agregan como conteo en `GET /api/v1/reports`,
  ver arriba).

---

### Ingesta de incidentes — MQTT (flujo activo) vs HTTP (legacy)

El CV Module ya no llama a `POST /api/v1/incidents` — publica por MQTT y el
backend escucha (`MqttIncidentSubscriber`, suscrito a `safevision/+/incidents`
y `safevision/+/incidents/clips`). La foto/clip ya están en S3, el mensaje
solo trae la referencia.

```json
// safevision/{siteId}/incidents — inmediato, foto ya subida a S3
{
  "incident_id": "uuid-generado-por-cv",
  "worker_code": 3,
  "missing_epp": ["helmet", "vest"],
  "timestamp": "2026-06-24T13:30:00",
  "camera_code": "CAM-01",
  "site_name": "Main-Site",
  "photo_s3_key": "incidents/2026-08-10/uuid-generado-por-cv/photo.jpg"
}
```
```json
// safevision/{siteId}/incidents/clips — minutos despues, correlacionado por incident_id
{
  "incident_id": "uuid-generado-por-cv",
  "s3_key": "incidents/2026-08-10/uuid-generado-por-cv/clip.mp4",
  "duration_seconds": 10.0,
  "file_size_bytes": 4831201
}
```

Payload legacy `POST /api/v1/incidents` (sigue existiendo, sin uso activo):

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

### Reglas EPP — MQTT retained (reemplaza el webhook HTTP de HU04)

`MqttRulesPublisher` publica (retained) `{"required_epp": [...]}` en
`safevision/{siteId}/rules` cuando cambian las reglas de una obra
(`PUT /api/v1/parameters/{siteId}`) — el CV se suscribe una vez y recibe el
último valor apenas se conecta, sin polling. `CvNotificationService`/
`CvProperties` (el webhook HTTP viejo, `CV_RELOAD_URL`) se eliminaron: ya no
tenían sentido una vez que el CV dejó de exponer ese endpoint.

### Config de cámara — MQTT retained (rtsp_url + active dinámicos)

Mismo patrón que las reglas EPP, para el problema de "¿a qué URL se conecta
el CV y debería seguir intentando?": `MqttCameraConfigPublisher` publica
(retained) `{"rtsp_url": "...", "active": true|false}` en
`safevision/{siteId}/cameras/{cameraCode}/config` cada vez que
`CameraService.create/update/delete` toca una cámara. El CV se suscribe por
`CAMERA_ID` y reemplaza su `VIDEO_SOURCE` estático por el `rtsp_url` de la
cámara en cuanto llega — y si `active=false`, deja de reintentar la conexión
por completo (reintentar sería en vano) hasta que vuelva a activarse. Ver
`RTSPCapture._wait_while_inactive` / `MqttCameraConfigSubscriber` del lado CV.

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
-- external_id: UUID generado por el CV, correlaciona el mensaje de "clip
-- listo" (llega minutos despues por MQTT) con el incidente ya persistido.
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

-- Evidencia visual: foto y/o clip de video (inmutable)
-- evidence_type: 'PHOTO' | 'VIDEO'. frame_b64 solo lo usa el flujo HTTP
-- legacy; storage_key/duration_seconds/file_size_bytes solo el flujo MQTT/S3.
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
- `notification_channels`: `TELEGRAM`
- `notification_statuses`: `PENDING`, `SENT`, `FAILED`
- `epp_parameters`: `casco`, `chaleco`, `guantes`
- `site_epp_requirements`: vacío — se configura por obra desde el frontend

---

## Flujo de un Incidente (activo — MQTT)

```
CV Module
   │
   └─ MQTT safevision/{siteId}/incidents  (MqttIncidentSubscriber)
         │
         ├─ 1. Resolver worker_code + camera_code + site_name → IDs (JOIN a BD)
         ├─ 2. Persistir en `incidents` (con external_id = incident_id del CV)
         ├─ 3. Persistir evidencia PHOTO en `evidence` (storage_key = photo_s3_key)
         ├─ 4. Resolver contactos de la obra (site_contacts, fallback a TELEGRAM_CHAT_ID)
         ├─ 5. EvidencePresignService → URL prefirmada de S3 (foto)
         ├─ 6. TelegramNotificationService.sendIncidentAlertByUrl → Telegram Bot API
         └─ 7. Persistir en `notifications` (SENT / FAILED por contacto)

   └─ MQTT safevision/{siteId}/incidents/clips  (minutos despues)
         ├─ 1. Buscar incidente por external_id
         └─ 2. Persistir evidencia VIDEO en `evidence` — no notifica de nuevo
```

El flujo HTTP legacy (`POST /api/v1/incidents`) sigue existiendo y hace lo
mismo pero con `frame_b64` inline en vez de `storage_key` — ver
`IncidentService.register()`.

> **Nota histórica:** la sirena física vía Hikvision ISAPI (HU09) estaba
> planificada pero se descartó del alcance — `HikvisionProperties` nunca
> llegó a tener un cliente HTTP real, y se eliminó del código. El guardado
> de evidencia en clips de video (ver flujo `.../incidents/clips` arriba)
> es la funcionalidad que efectivamente se entregó en su lugar.

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

# Seguridad
ALERT_SERVICE_TOKEN=    # Bearer token que valida el módulo CV (solo el endpoint HTTP legacy)
JWT_SECRET=              # Firma los JWT de /api/v1/auth/login — mínimo 32 bytes, nunca commitear el valor real
JWT_EXPIRATION_MINUTES=480   # Vigencia del JWT (default 8h)

# MQTT (mensajeria CV<->Backend — incidentes/clips/reglas)
MQTT_BROKER_HOST=
MQTT_BROKER_PORT=1883
MQTT_USERNAME=
MQTT_PASSWORD=
MQTT_CLIENT_ID=

# S3 (evidencia: foto + clip, subidos por el CV — el backend solo lee/presigna)
S3_BUCKET=
AWS_REGION=us-east-1
S3_PRESIGN_TTL_MINUTES=15

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
| Evidencia    | AWS S3 (bucket nuevo)  | Foto+clip subidos por el CV, lifecycle 60 días — ver `infra/cloudformation/safevision-stack-mqtt.yaml` |
| Broker MQTT  | Mosquitto en Docker    | Corre junto al backend en su misma EC2 (`mosquitto.service`), auth anónima acotada por Security Group |
| Secretos     | AWS Secrets Manager   | Tokens, credenciales de BD            |
| CI/CD        | GitHub Actions        | Build → Test → Deploy                  |

`infra/cloudformation/safevision-stack.yaml` (el original, HTTP legacy) y
`safevision-stack-mqtt.yaml` (nuevo, MQTT+S3) coexisten — el segundo es el
que se usa para deploys nuevos, el primero queda intacto sin uso, mismo
criterio "no borrar" que el resto del proyecto. `infra/deploy.sh` e
`infra/README.md` todavía documentan el flujo del stack viejo — ajustarlos
para el nuevo stack es una tarea aparte, no resuelta todavía.

---

## Desarrollo local con Docker (Rancher Desktop / Docker Desktop)

Dos `docker-compose.yml` en la **raíz** del repo (no en `infra/` — esa
carpeta es solo para el deploy real a AWS vía CloudFormation, ver sección
anterior), mismo criterio de sufijo que `safevision-front` y
`safevision-computer-vision`:

- **[`docker-compose.yml`](docker-compose.yml)** (sin sufijo) — este
  proyecto y su compañero inseparable: `backend` + `mosquitto`, mismo
  criterio que en producción (ver "Infraestructura AWS" arriba — Mosquitto
  corre junto al backend en su misma EC2, nunca aparte). Postgres sí se
  asume externo (`host.docker.internal`, ver comentario en el archivo) —
  en AWS es RDS, un servicio manejado aparte, no algo que corra "junto"
  al backend.
  ```bash
  cp .env.example .env
  docker compose up --build
  ```
- **[`docker-compose.full.yml`](docker-compose.full.yml)** — el **flujo
  completo**: `postgres`, `mosquitto` (broker MQTT), `backend`, `frontend`
  (build del repo hermano `safevision-front`, `BACKEND_MODE=live`
  apuntando al `backend` de este mismo compose), `mediamtx` (servidor
  RTSP) y `cv-app` (build del repo hermano `safevision-computer-vision`).
  `camera-simulator` (perfil `manual`, no arranca con `up`) publica un
  video de prueba por RTSP a `mediamtx` vía ffmpeg para simular una cámara
  real — alternativa: apuntar `cv-app` a un stream publicado a mano con
  VLC media player, ver `docker-compose.yml` de `safevision-computer-vision`.
  ```bash
  docker compose -f docker-compose.full.yml up --build
  # en otra terminal, para "encender la cámara" una vez que todo está arriba:
  docker compose -f docker-compose.full.yml run --rm camera-simulator
  ```

Variables opcionales en `.env` (ver [`.env.example`](.env.example), compartido
por ambos compose — cada uno solo lee las que le aplican):
`CV_REPO_PATH`/`FRONTEND_REPO_PATH` (rutas a los repos hermanos, solo usadas
por `docker-compose.full.yml`), tokens de Telegram, `FLASK_SECRET_KEY`,
credenciales de S3 real (sin esto el CV no sube evidencia y no se genera
ningún incidente de punta a punta).

---

## Convenciones de Código

- **Java 21** — records para DTOs/domain, sealed classes y pattern matching.
- **Nombres en inglés** — código, tablas, columnas, variables, métodos.
- **Comentarios y Javadoc en español.**
- **Reactive first** — cero `.block()` en el flujo principal. Reactor puro (`Mono`/`Flux`), sin RxJava.
- **Hexagonal** — `application/service/*` depende de interfaces en
  `application/ports/out/`, nunca de una clase concreta de `infrastructure/`.
  Los adaptadores concretos (`TelegramNotificationService`,
  `EvidencePresignService`, `MqttRulesPublisher`, los 15 repos Spring Data)
  implementan esos puertos explícitamente (`implements XPort`).
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
- **Estado verificado (2026-08-10, post-refactor hexagonal):** 140 tests, 0 fallos, 0 errores, 1 omitido.
- Tests actuales son unitarios (controller con `WebTestClient` + Mockito, service con StepVerifier/Mockito) y espejan la estructura de `src/main/` 1:1 bajo `src/test/`.
- `Testcontainers` está en `pom.xml` pero **aún no hay tests de integración que lo usen** — pendiente antes de cerrar esa parte del checklist.
- Mocks para Telegram en tests unitarios (`TelegramNotificationServiceTest`, `TelegramNotificationPerformanceTest`) — `TelegramNotificationService` recibe `WebClient.Builder` inyectado en vez de construir su propio `WebClient`, para poder mockear el `ExchangeFunction` sin llamadas HTTP reales.
- `MqttIncidentSubscriberTest`/`MqttRulesPublisherTest`/`EvidencePresignServiceTest` mockean `MqttClient` (Paho) y `S3Presigner` (AWS SDK) directamente — sin broker ni bucket reales.

---

## Relación con Otros Módulos

| Dirección          | Endpoint / Canal           | Qué hace                              |
|--------------------|----------------------------|---------------------------------------|
| CV → Backend       | MQTT `safevision/{siteId}/incidents` | Incidente confirmado, foto ya en S3 |
| CV → Backend       | MQTT `safevision/{siteId}/incidents/clips` | Clip de video listo (minutos después) |
| Backend → CV       | MQTT `safevision/{siteId}/rules` (retained) | Reglas EPP activas de la obra — push, no polling |
| Backend → CV       | MQTT `safevision/{siteId}/cameras/{code}/config` (retained) | `rtsp_url` + `active` de la cámara — push, no polling |
| CV → Backend       | `POST /api/v1/incidents` (legacy) | Sin uso activo, se deja sin borrar |
| Frontend → Backend | `POST /api/v1/auth/login`  | Login — único endpoint público, retorna JWT |
| Frontend → Backend | Todos los demás endpoints REST | Gestión de datos maestros, requieren `Authorization: Bearer <jwt>` (reportes aún no implementados) |
| Backend → Telegram | Telegram Bot API           | Alerta push a los contactos de la obra (URL prefirmada de S3 o bytes inline) |
| Backend ↔ AWS S3   | AWS SDK v2 (`S3Presigner`) | Solo lectura — URLs prefirmadas de evidencia subida por el CV |
| Backend → AWS RDS  | R2DBC                      | Persiste todos los eventos            |

---

## Estado Actual

- [x] Proyecto Spring Boot 3 inicializado con dependencias completas
- [x] Esquema DB creado (`docs/init-schema.sql`) — sin Flyway migrations todavía (script plano, no versionado por migration tool)
- [x] Entidades de dominio definidas (records Java 21) — `Site`, `Zone`, `Camera`, `Worker`, `User`, `UserRole`, `SiteContact`, `EppParameter`, `SiteEppRequirement`, `Incident`, `Evidence`, `Notification`, `NotificationChannel`, `NotificationStatus`
- [x] CRUD Sites implementado
- [x] CRUD Cameras implementado
- [x] Config de cámara (`rtsp_url`/`active`) push al CV por MQTT retained (`MqttCameraConfigPublisher`) — el CV ya no depende de `VIDEO_SOURCE` fijo por variable de entorno ni reintenta conectar a cámaras inactivas
- [x] CRUD Workers implementado
- [x] CRUD Users implementado
- [x] CRUD Zones implementado (anidado bajo obra)
- [x] CRUD Site Contacts implementado (anidado bajo obra)
- [x] Endpoint POST /api/v1/incidents (HU10)
- [x] Endpoint GET /api/v1/incidents (HU10)
- [x] Endpoint GET/PUT /api/v1/parameters/{siteId} (HU04) — por obra, con fallback al catálogo global
- [x] Reportes y estadísticas implementados (HU12) — `GET /api/v1/reports`,
      agregación real con `DatabaseClient` (sin "% de cumplimiento", ver
      sección de arriba)
- [x] Servicio Telegram Bot (`TelegramNotificationService`) — notifica a los contactos de la obra
- [x] Notificación de incidentes extraída a `IncidentNotificationService` — mantiene `IncidentService` bajo el límite de 150 líneas
- [x] Swagger/OpenAPI configurado (`OpenApiConfig`)
- [x] Seguridad Bearer estática — protege `POST /api/v1/incidents` (`ALERT_SERVICE_TOKEN`, módulo CV)
- [x] Login JWT (`POST /api/v1/auth/login`, público) — `AuthService`/`AuthController`/`JwtService`; el resto de endpoints exige `Authorization: Bearer <jwt>` vía `SecurityConfig.jwtAuthFilter` (2026-08-15)
- [ ] Autorización por rol (ADMIN/SUPERVISOR) — el JWT lleva el claim `role`, pero ningún endpoint lo verifica todavía; hoy cualquier JWT válido pasa
- [x] Todas las respuestas envueltas en `ApiEnvelope<T>` (`{status, datetime, error, data}` / `{..., errorCode, errorDescription}`) — ver sección [Formato de Respuesta](#formato-de-respuesta--apienvelopet-2026-08-15); DELETE pasó de 204 a 200
- [x] Tests unitarios — 162 tests, 0 fallos, 1 omitido (verificado 2026-08-16
      con `./mvnw test`, incluye `JwtServiceTest`/`AuthServiceTest`/
      `AuthControllerTest`, los 9 `*ControllerTest` del envelope, y
      `ReportServiceTest`/`ReportControllerTest` de HU12)
- [ ] Tests de integración Testcontainers — dependencia agregada, sin tests que la usen aún
- [x] Infraestructura como código (CloudFormation) — RDS + 2 EC2 (backend, CV), bootstrap 100% automático vía UserData, pull de imágenes Docker desde ECR (`infra/`); pensada para crearse/borrarse por sesión de demo
- [x] Checkstyle + SpotBugs + Spotless integrados al build (no bloquean, `failOnViolation=false`)
- [x] Arquitectura hexagonal adoptada — `domain/`, `application/` (services + ports), `infrastructure/` (web, persistence, messaging, notification, storage, config)
- [x] Ingesta de incidentes/clips por MQTT (`MqttIncidentSubscriber`) — reemplaza `POST /api/v1/incidents` como camino activo del CV; el endpoint HTTP sigue existiendo, sin uso
- [x] Reglas EPP por MQTT retained (`MqttRulesPublisher`) — reemplaza el webhook HTTP de HU04; `CvNotificationService`/`CvProperties` eliminados
- [x] Evidencia (foto+clip) en S3, subida por el CV — backend solo genera URLs prefirmadas (`EvidencePresignService`) para Telegram y el endpoint `GET /api/v1/incidents/{id}/evidence`
- [x] `incidents.external_id` — correlaciona el mensaje MQTT de clip (llega después) con el incidente ya persistido
- [ ] `infra/deploy.sh` / `infra/README.md` actualizados para el nuevo stack MQTT+S3 — pendiente, documentado como deuda
