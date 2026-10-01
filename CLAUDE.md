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
| JaCoCo               | Cobertura de tests (`mvn test` genera `target/site/jacoco/`; `check` en `verify` bloquea si LINE < 70% o BRANCH < 60%) |
| Checkstyle / SpotBugs | Análisis estático (local `fail=false`, no bloquean; en CI sí bloquean vía override `-Dcheckstyle.failOnViolation=true -Dspotbugs.failOnError=true`) |
| Spotless              | Formateo automático (`spotless-maven-plugin`) — orden de imports, indentación, fin de línea; reglas livianas, sin preset Google (en CI `spotless:check` bloquea) |
| Testcontainers       | Tests de integración con Postgres real (`IncidentRepositoryIntegrationTest`, `SiteEppConfigVersionIntegrationTest`; requieren Docker encendido) |

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

**Decisión pragmática documentada:** los 15 records de `domain/model/`
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
│       ├── IncidentService              ← ingesta: persiste incidente+evidencia,
│       │                                    register/registerFromCv/registerClipReady
│       └── IncidentQueryService         ← consulta: findByFilter/findById/findEvidenceByIncidentId
│                                            (separado de IncidentService por el límite de 150 líneas)
└── infrastructure/
    ├── web/                 ← Controllers (driving) + dto/ (Request/Response)
    │   ├── SiteController, ZoneController, SiteContactController
    │   ├── CameraController, WorkerController, UserController
    │   ├── IncidentController        ← HTTP legacy (frame_b64 inline) + GET .../evidence
    │   │                                + POST /api/v1/cv/incidents(/clips) (flujo activo, dto/
    │   │                                CvIncidentMessage, CvClipReadyMessage)
    │   └── EppParameterController
    ├── persistence/         ← 15 repos Spring Data, cada uno
    │                            `extends XRepositoryPort {}` (implementación via proxy)
    ├── webhook/              ← HttpRulesPublisher, HttpCameraConfigPublisher (implementan
    │                            RulesPublisherPort/CameraConfigPublisherPort — notifican
    │                            al CV por HTTP directo a camera.ipAddress, sin broker)
    ├── notification/         ← TelegramNotificationService (implementa NotificationChannelPort)
    ├── storage/              ← EvidencePresignService (implementa EvidenceStoragePort, S3 URLs prefirmadas)
    └── config/                ← OpenApiConfig, SecurityConfig, TelegramProperties, S3Properties
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

## Entidades del Dominio

`Site`, `Zone`, `Camera`, `Worker`, `User`, `UserRole`, `SiteContact`,
`EppParameter`, `SiteEppRequirement`, `SiteEppConfigVersion`, `Incident`, `Evidence`, `Notification`,
`NotificationChannel`, `NotificationStatus` (`Long` IDENTITY; `incidents.external_id` es UUID de correlación).

---

## API Endpoints

El prefijo `/api/v1` no se repite en cada `@RequestMapping` — es
`spring.webflux.base-path` (`application.yml`), una sola vez para toda la
app. Las URLs reales (las de esta tabla) no cambian; los `@RequestMapping`
de cada controller sí quedan sin el prefijo (`@RequestMapping("/sites")`,
no `"/api/v1/sites"`). `SecurityConfig` no se tocó: los `WebFilter` ven el
path completo igual, `base-path` solo afecta el matching de `@RequestMapping`.

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
| GET    | `/api/v1/sites/{siteId}/contacts`          | Listar contactos de la obra, activos e inactivos (alertas Telegram) |
| POST   | `/api/v1/sites/{siteId}/contacts`          | Crear contacto                       |
| PUT    | `/api/v1/sites/{siteId}/contacts/{contactId}` | Actualizar contacto                |
| PUT    | `/api/v1/sites/{siteId}/contacts/{contactId}/active` | Activar/desactivar contacto (pausa alertas sin tocar el vínculo de Telegram — ej. vacaciones) |
| DELETE | `/api/v1/sites/{siteId}/contacts/{contactId}` | Desactivar contacto (equivalente a `.../active` con `active:false`) |
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
| POST   | `/api/v1/cv/incidents`          | HU10  | **Activo** — el CV manda el incidente con evidencia ya en S3 (`photo_s3_key`); Bearer token (`ALERT_SERVICE_TOKEN`) |
| POST   | `/api/v1/cv/incidents/clips`    | HU10  | **Activo** — aviso de "clip listo" (llega minutos después, correlacionado por `incident_id`); mismo Bearer token |
| POST   | `/api/v1/incidents`             | HU10  | **Legacy** — sin uso activo (payload con `frame_b64` inline, sin S3); sigue funcionando con el mismo Bearer token; se deja sin borrar por si hace falta volver atrás |
| GET    | `/api/v1/incidents`             | HU10  | Lista **paginada** de incidentes (filtros opcionales: `siteId`, `workerId`, `from`, `to` — datetime ISO completo, con hora; `page` desde 1 default 1; `size` default 20, máximo 50). `data` es `{items, page, size, totalItems, totalPages}`, no un array plano |
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

**Username del actor autenticado — atributo del exchange, no header.**
`jwtAuthFilter` extrae el `subject` del JWT ya validado
(`JwtService.extractUsername`) y lo deja en `exchange.getAttributes()`; los
controllers que necesitan quién hizo el cambio (`createdBy`/`updatedBy`) lo
leen con `@RequestAttribute("username")`. Antes viajaba en un header
`X-Username` que el cliente mandaba por su cuenta — sin relación con el JWT,
así que cualquiera podía mandar un valor distinto al usuario real. Ya no
existe ese header.

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

### Ingesta de incidentes — HTTP directo (flujo activo) vs legacy inline

> **Nota histórica:** entre 2026-08 y esta versión, este flujo pasó por MQTT
> (`MqttIncidentSubscriber`, broker `eclipse-mosquitto`). Se revirtió a HTTP
> directo para no depender de un servidor de broker adicional — ver tag git
> `mqtt-pre-https-mosquitto2` para el estado completo de esa arquitectura si
> hiciera falta volver a mirarla.

El CV Module llama a `POST /api/v1/cv/incidents` (`IncidentController`) — la
foto ya está en S3, el payload solo trae la referencia. Mismo Bearer token
(`ALERT_SERVICE_TOKEN`) que el resto de la ingesta.

```json
// POST /api/v1/cv/incidents — inmediato, foto ya subida a S3
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
// POST /api/v1/cv/incidents/clips — minutos despues, correlacionado por incident_id
{
  "incident_id": "uuid-generado-por-cv",
  "s3_key": "incidents/2026-08-10/uuid-generado-por-cv/clip.mp4",
  "duration_seconds": 10.0,
  "file_size_bytes": 4831201
}
```

Payload legacy `POST /api/v1/incidents` (sigue existiendo, sin uso activo,
sin S3, evidencia inline en base64):

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

### Reglas EPP — webhook HTTP (reemplaza el retained MQTT)

`HttpRulesPublisher` (implementa `RulesPublisherPort`) hace POST best-effort
de `{"required_epp": [...]}` a `http://{camera.ipAddress}:{CV_WEBHOOK_PORT}/webhook/rules`
por cada cámara activa de la obra (fan-out — una obra puede tener varias
cámaras/instancias del CV) cuando cambian las reglas
(`PUT /api/v1/parameters/{siteId}`). Un fallo (CV apagado, red caída) se
loguea y no bloquea ni propaga error — mismo criterio best-effort que tenía
el publisher MQTT.

### Config de cámara — webhook HTTP (rtsp_url + active dinámicos)

Mismo patrón que las reglas EPP: `HttpCameraConfigPublisher` (implementa
`CameraConfigPublisherPort`) hace POST best-effort de
`{"camera_code": "...", "rtsp_url": "...", "active": true|false}` a
`http://{camera.ipAddress}:{CV_WEBHOOK_PORT}/webhook/config` cada vez que
`CameraService.create/update/delete` toca una cámara — pero solo si ya
tiene zona asignada (`zoneId != null`, ver `CameraService.publishConfig`,
sección "Estado Actual"). El CV expone ese webhook con un pequeño servidor
HTTP propio en su mismo proceso (no un contenedor aparte) y reemplaza su
`VIDEO_SOURCE` estático por el `rtsp_url` recibido — si `active=false`, deja
de reintentar la conexión hasta que vuelva a activarse.

### Vinculación de supervisores por Telegram (código corto + polling)

Un `site_contact` sin `telegram_chat_id` recibe automáticamente un
`telegram_link_code` (6 dígitos, `SecureRandom`) al crearse o editarse —
`SiteContactService.create/update`. El frontend lo muestra en un popup con
los pasos (`app/templates/supervisores.html`). El supervisor le manda un
único mensaje `/start <código>` a `@safevision_epp_bot` (deep-link estándar
de Telegram — un solo mensaje, no dos); `TelegramLinkingPoller`
(`infrastructure/notification/`) hace short polling a `getUpdates` de la
Bot API (mismo `TELEGRAM_BOT_TOKEN`, sin webhook público), extrae el
código del mensaje (tolera `/start <código>`, `/start@bot <código>` o el
código solo, sin `/start` — ver `TelegramLinkingPoller.extractLinkCode`) y
lo compara contra `telegram_link_code` vía `SiteContactService.tryLinkByCode`;
si matchea
setea `telegram_chat_id` + limpia el código + responde por Telegram
confirmando. Si no matchea, responde con un mensaje genérico. Intervalo
parametrizable — `app.telegram.linking-poll-interval-ms`
(`TELEGRAM_LINKING_POLL_INTERVAL_MS`), default 900000 ms (15 min). El
offset de `getUpdates` se guarda en memoria (no en BD) — reprocesar un update viejo
tras un reinicio es inofensivo (el código ya fue consumido y no matchea, o
vuelve a linkear sin causar daño), así que no se justifica persistirlo
todavía. El camino manual (pegar el `telegram_chat_id` a mano al crear/editar
el contacto) sigue existiendo — si viene en el request, no se genera código.

**Pausar alertas sin perder el vínculo (ej. vacaciones):**
`PUT /api/v1/sites/{siteId}/contacts/{contactId}/active` (`{"active": bool}`)
activa/desactiva un contacto sin tocar `telegram_chat_id`/`telegram_link_code`
— a diferencia de `DELETE` (mismo efecto en un sentido, pero pensado como
"eliminar"), este endpoint sirve también para reactivar. `IncidentNotificationService`
ya filtraba por `active=true` desde antes, así que un contacto pausado deja
de recibir alertas de inmediato sin ningún cambio adicional. `GET
/sites/{siteId}/contacts` ahora devuelve activos e inactivos (antes solo
activos) — la pantalla de gestión necesita ver a quién puede reactivar.
El frontend (`app/templates/supervisores.html`) lo expone como un switch
por fila (reutiliza el componente `.switch` que ya existía en el CSS, sin
usar hasta ahora) en vez del botón "Eliminar" de antes.

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
-- code: identificador de negocio estable — autogenerado si no se especifica
-- al crear, inmutable despues (el CV lo usa para identificarse en cada
-- evento HTTP que manda).
CREATE TABLE sites (
    id         BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code       VARCHAR(50)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL UNIQUE,
    location   VARCHAR(200),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(100)
);

-- Zonas dentro de una obra (ej. "Piso 2", "Almacén") — agrupan cámaras.
-- code: unico por obra (no global), mismo criterio de inmutabilidad que sites.code.
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

-- Cámaras IP — zone_id nulo = registrada pero sin enlazar todavia (el
-- backend no notifica su config al CV hasta que tenga zona asignada).
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
-- external_id: UUID generado por el CV, correlaciona el aviso de "clip
-- listo" (llega minutos despues, POST separado) con el incidente ya persistido.
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
-- legacy; storage_key/duration_seconds/file_size_bytes solo el flujo nuevo
-- del CV con evidencia en S3.
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
-- telegram_link_code: código de 6 dígitos generado si no vino telegram_chat_id
-- (ver sección "Vinculación de supervisores por Telegram" más arriba).
-- Fallback: si la obra no tiene contactos con telegram_chat_id, se usa
-- TELEGRAM_CHAT_ID del entorno.
CREATE TABLE site_contacts (
    id                 BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    site_id            BIGINT       NOT NULL REFERENCES sites(id),
    name               VARCHAR(100) NOT NULL,
    phone              VARCHAR(20)  NOT NULL,
    telegram_chat_id   VARCHAR(100),
    telegram_link_code VARCHAR(10),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by         VARCHAR(100)
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

## Flujo de un Incidente (activo — HTTP)

```
CV Module
   │
   └─ POST /api/v1/cv/incidents  (IncidentController → IncidentService.registerFromCv)
         │
         ├─ 0. Validar el payload (CvIncidentMessage: @NotEmpty missing_epp, @NotBlank
         │     incident_id/camera_code/site_name/photo_s3_key, ...) → 400 si no cumple (CP31)
         ├─ 1. Resolver worker_code + camera_code + site_name → IDs (JOIN a BD)
         ├─ 2. Persistir en `incidents` (con external_id = incident_id del CV)
         ├─ 3. Persistir evidencia PHOTO en `evidence` (storage_key = photo_s3_key)
         ├─ 4. Resolver contactos de la obra (site_contacts, fallback a TELEGRAM_CHAT_ID)
         ├─ 5. EvidencePresignService → URL prefirmada de S3 (foto)
         ├─ 6. TelegramNotificationService.sendIncidentAlertByUrl → Telegram Bot API
         │     (caption: trabajador, EPP faltante, obra, zona, cámara, fecha/hora — CP28;
         │     reintento básico ante 429/5xx/falla de red: 2 reintentos con backoff
         │     exponencial desde 1 s, un 4xx distinto de 429 no se reintenta — CP29)
         └─ 7. Persistir en `notifications` (SENT / FAILED por contacto, FAILED si se agotan los reintentos)

   └─ POST /api/v1/cv/incidents/clips  (minutos despues)
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
TELEGRAM_LINKING_POLL_INTERVAL_MS=900000   # Intervalo del polling de vinculación (TelegramLinkingPoller), default 15 min

# Seguridad
ALERT_SERVICE_TOKEN=    # Bearer token que valida el módulo CV (endpoints /api/v1/incidents y /api/v1/cv/**)
JWT_SECRET=              # Firma los JWT de /api/v1/auth/login — mínimo 32 bytes, nunca commitear el valor real
JWT_EXPIRATION_MINUTES=480   # Vigencia del JWT (default 8h)

# Webhook HTTP hacia el CV (reglas EPP + config de cámara — ver CLAUDE.md)
CV_WEBHOOK_PORT=5001    # Puerto donde el CV expone su propio webhook (mismo proceso, sin broker)

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
| Evidencia    | AWS S3 (bucket nuevo)  | Foto+clip subidos por el CV, lifecycle 60 días |
| Secretos     | AWS Secrets Manager   | Tokens, credenciales de BD            |
| CI/CD        | GitHub Actions        | Build → Test → Deploy                  |

---

## Desarrollo local con Docker (Rancher Desktop / Docker Desktop)

Dos `docker-compose.yml` en la **raíz** del repo (no en `infra/` — esa
carpeta es solo para el deploy real a AWS vía CloudFormation, ver sección
anterior), mismo criterio de sufijo que `safevision-front` y
`safevision-computer-vision`:

- **[`docker-compose.yml`](docker-compose.yml)** (sin sufijo) — solo el
  backend, sin dependencias adicionales (la comunicación con el CV es HTTP
  directo, no hay broker que levantar junto al backend). Postgres sí se
  asume externo (`host.docker.internal`, ver comentario en el archivo) —
  en AWS es RDS, un servicio manejado aparte, no algo que corra "junto"
  al backend.
  ```bash
  cp .env.example .env
  docker compose up --build
  ```
- **[`docker-compose.full.yml`](docker-compose.full.yml)** — el **flujo
  completo**: `postgres`, `backend`, `frontend`
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
  `EvidencePresignService`, `HttpRulesPublisher`, los 15 repos Spring Data)
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
- **Estado verificado (2026-09-22, revisión de CP):** 203 tests con `./mvnw verify`, 0 fallos, 0 errores, 1 omitido. Los tests de Testcontainers (`IncidentRepositoryIntegrationTest`, `SiteEppConfigVersionIntegrationTest`) necesitan un motor Docker encendido (Rancher/Docker Desktop); sin Docker fallan con "Could not find a valid Docker environment".
- La inmensa mayoría son unitarios (controller con `WebTestClient` + Mockito, service con StepVerifier/Mockito) y espejan la estructura de `src/main/` 1:1 bajo `src/test/`.
- `Testcontainers` (`org.testcontainers:testcontainers-junit-jupiter`/`testcontainers-postgresql`/`testcontainers-r2dbc`, v2.0.2 — la línea 1.x no soporta motores Docker recientes con `MinAPIVersion` ≥ 1.41, ver nota en `IncidentRepositoryIntegrationTest`) tiene un primer uso real:
  `IncidentRepositoryIntegrationTest` levanta un `PostgreSQLContainer` real, carga el schema desde `docs/init-schema.sql` (única fuente de verdad, sin copia paralela en `test/resources`) vía JDBC (`org.postgresql:postgresql`, dependencia de test — la app en sí sigue 100% R2DBC) y verifica `findByFilterPaged`/`countByFilter`
  (`IncidentRepositoryPort`): el SQL crudo con `LIMIT`/`OFFSET` y filtros opcionales que un repositorio mockeado no puede probar.
  `SiteEppConfigVersionIntegrationTest` (CP18) usa el mismo patrón para probar que el `compareAndSwap` de
  `site_epp_config_versions` es atómico contra Postgres real: 20 rondas de dos threads con la misma versión
  esperada, siempre gana exactamente uno (`EppParameterServiceTest` solo simula el CAS con un `AtomicBoolean`). Corre con `@SpringBootTest` + `@ActiveProfiles("dev")` — el `application.yml` de test (sin perfil activo, pensado para tests unitarios que no levantan contexto completo) no trae `s3.*`/`telegram.*`, necesarios para que el `ApplicationContext` arranque entero.
- Mocks para Telegram en tests unitarios (`TelegramNotificationServiceTest`, `TelegramNotificationPerformanceTest`, `TelegramLinkingPollerTest`) — `TelegramNotificationService`/`TelegramLinkingPoller` reciben `WebClient.Builder` inyectado en vez de construir su propio `WebClient`, para poder mockear el `ExchangeFunction` sin llamadas HTTP reales. Mismo patrón en `HttpRulesPublisherTest`/`HttpCameraConfigPublisherTest`.
- `EvidencePresignServiceTest` mockea `S3Presigner` (AWS SDK) directamente — sin bucket real.

---

## Relación con Otros Módulos

| Dirección          | Endpoint / Canal           | Qué hace                              |
|--------------------|----------------------------|---------------------------------------|
| CV → Backend       | `POST /api/v1/cv/incidents` | Incidente confirmado, foto ya en S3 |
| CV → Backend       | `POST /api/v1/cv/incidents/clips` | Clip de video listo (minutos después) |
| Backend → CV       | `POST http://{camera.ip}:{CV_WEBHOOK_PORT}/webhook/rules` | Reglas EPP activas de la obra — push best-effort, no polling |
| Backend → CV       | `POST http://{camera.ip}:{CV_WEBHOOK_PORT}/webhook/config` | `rtsp_url` + `active` de la cámara — push best-effort, no polling |
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
- [x] Config de cámara (`rtsp_url`/`active`) notificada al CV por webhook HTTP (`HttpCameraConfigPublisher`) — el CV ya no depende de `VIDEO_SOURCE` fijo por variable de entorno ni reintenta conectar a cámaras inactivas. Solo se notifica si la cámara ya tiene zona asignada (`zoneId != null`) — sin zona queda registrada pero "sin enlazar"
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
- [x] Notificación de incidentes extraída a `IncidentNotificationService` y consultas a `IncidentQueryService` — mantiene `IncidentService` (ingesta) bajo el límite de 150 líneas
- [x] Swagger/OpenAPI configurado (`OpenApiConfig`)
- [x] Seguridad Bearer estática — protege `POST /api/v1/incidents` (`ALERT_SERVICE_TOKEN`, módulo CV)
- [x] Login JWT (`POST /api/v1/auth/login`, público) — `AuthService`/`AuthController`/`JwtService`; el resto de endpoints exige `Authorization: Bearer <jwt>` vía `SecurityConfig.jwtAuthFilter` (2026-08-15)
- [ ] Autorización por rol (ADMIN/SUPERVISOR) — el JWT lleva el claim `role`, pero ningún endpoint lo verifica todavía; hoy cualquier JWT válido pasa
- [x] Todas las respuestas envueltas en `ApiEnvelope<T>` (`{status, datetime, error, data}` / `{..., errorCode, errorDescription}`) — ver sección [Formato de Respuesta](#formato-de-respuesta--apienvelopet-2026-08-15); DELETE pasó de 204 a 200
- [x] Tests — 203 tests, 0 fallos, 1 omitido (`./mvnw verify` con Docker encendido, ver sección Testing), incluye `JwtServiceTest`/`AuthServiceTest`/
      `AuthControllerTest`, los 9 `*ControllerTest` del envelope,
      `ReportServiceTest`/`ReportControllerTest` de HU12,
      `HttpRulesPublisherTest`/`HttpCameraConfigPublisherTest`, y
      `IncidentRepositoryIntegrationTest` (Testcontainers, ver sección
      Testing)
- [x] Vinculación de supervisores por Telegram vía código corto + polling
      (`TelegramLinkingPoller`, `SiteContactService.tryLinkByCode`) — ver
      sección "Vinculación de supervisores por Telegram" más arriba; el
      camino manual (pegar `telegram_chat_id`) sigue existiendo como
      fallback. Sin envío por WhatsApp/correo — evaluado y descartado por
      ahora (WhatsApp Business API exige verificación de negocio en Meta y
      plantillas pre-aprobadas, desproporcionado para el alcance de la tesis)
- [x] Tests de integración Testcontainers — `IncidentRepositoryIntegrationTest` (Postgres real vía `PostgreSQLContainer`, schema cargado desde `docs/init-schema.sql`) verifica `findByFilterPaged`/`countByFilter`, el SQL crudo con LIMIT/OFFSET que un repositorio mockeado no puede probar
- [x] Infraestructura como código (CloudFormation) — RDS + 2 EC2 (backend, CV), bootstrap 100% automático vía UserData, pull de imágenes Docker desde ECR (`infra/`); pensada para crearse/borrarse por sesión de demo
      — actualizada 2026-09-22 a la arquitectura actual: CV con exp3 INT8 publicando a
      `/api/v1/cv/incidents` y evidencia en un bucket S3 persistente (`EVIDENCE_BUCKET`,
      privado, expira a 60 días), backend con `JWT_SECRET`/`S3_BUCKET`/`CV_WEBHOOK_PORT`,
      un rol IAM por instancia (CV escribe evidencia, backend la lee para presignar), y el
      enlace backend → CV automático: el bootstrap del backend guarda la IP privada del CV
      en `cameras.ip_address` (`/opt/safevision/wire-camera.sh`, respaldo `./deploy.sh wire`)
- [x] Checkstyle + SpotBugs + Spotless integrados al build (no bloquean, `failOnViolation=false`)
- [x] Arquitectura hexagonal adoptada — `domain/`, `application/` (services + ports), `infrastructure/` (web, persistence, webhook, notification, storage, config)
- [x] Ingesta de incidentes/clips por HTTP directo (`IncidentController`, `POST /api/v1/cv/incidents(/clips)`) — reemplaza `POST /api/v1/incidents` como camino activo del CV; el endpoint legacy sigue existiendo, sin uso. Ambos flujos viven en el mismo controller (antes separados en `CvIngestController`, fusionado — mismo recurso REST `/incidents`, un controller por recurso, mismo criterio que Site/Zone/Camera)
- [x] Reglas EPP por webhook HTTP (`HttpRulesPublisher`) — reemplaza el retained MQTT (que a su vez había reemplazado el webhook HTTP original de HU04); vuelve al mismo patrón de entrega pero con evidencia S3 y payload moderno
- [x] Evidencia (foto+clip) en S3, subida por el CV — backend solo genera URLs prefirmadas (`EvidencePresignService`) para Telegram y el endpoint `GET /api/v1/incidents/{id}/evidence`
- [x] `incidents.external_id` — correlaciona el aviso de clip (llega después) con el incidente ya persistido
- [x] Obras/zonas/cámaras con `code` inmutable (autogenerado o validado contra duplicados) — identificador de negocio estable que el CV manda en cada evento
- [x] `infra/cloudformation/safevision-stack-mqtt.yaml` (CloudFormation viejo, seguía aprovisionando un `mosquitto` que ya no hacía falta) eliminado — `infra/deploy.sh`/`infra/README.md` ya apuntaban únicamente a `safevision-stack.yaml` (el stack HTTP sin broker), no hacía falta tocarlos
