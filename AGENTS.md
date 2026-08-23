# AGENTS.md — SafeVision Backend

> Archivo de contexto para Codex y agentes de IA.
> Equivalente a CLAUDE.md — mantener ambos sincronizados.

---

## Descripción del Proyecto

Backend principal del sistema SafeVision: plataforma de monitoreo EPP en obras.
Recibe eventos desde el módulo de Computer Vision (Python/YOLOv11), gestiona datos
maestros (obras, cámaras, trabajadores, usuarios), genera reportes y envía
notificaciones vía Telegram, guardando evidencia (foto + clip) en S3.

Tesis de egreso — UPC | Ingeniería de Sistemas
Autores: Enzo Esteban Quispe / Yvette Flores Castillo

---

## Reglas no negociables

### Identidad en commits

- `user.name = "Enzo Esteban"` / `user.email = "u202417985@upc.edu.pe"`
- **PROHIBIDO:** `Co-authored-by: Codex`, menciones a IA, emojis decorativos.

### Operaciones prohibidas sin confirmación

- `git push`, `gh pr create`, `git merge` a `release`/`main`
- `git reset --hard` sobre commits ya pusheados
- Hardcodear tokens, contraseñas o credenciales

### Flujo esperado

1. Leer este archivo + archivo a modificar.
2. Explicar el cambio.
3. Aplicar el cambio.
4. Verificar: `./mvnw verify -q`
5. Sugerir commit — no ejecutar hasta aprobación.
6. Detenerse. No pushear.

---

## Stack Técnico

| Componente        | Detalle                                        |
|-------------------|------------------------------------------------|
| Java              | 21 (LTS)                                       |
| Spring Boot       | 3.x                                            |
| Spring WebFlux    | Capa web reactiva (Mono / Flux)               |
| Reactor           | Único stack reactivo — RxJava evaluado y descartado (nunca se implementó) |
| R2DBC             | Driver reactivo PostgreSQL                    |
| SpringDoc OpenAPI | Swagger UI `/swagger-ui.html`                 |
| Telegram Bot API  | Notificaciones push a contactos de la obra    |
| Maven             | Build tool                                     |
| JaCoCo / Checkstyle / SpotBugs / Spotless | Cobertura, análisis estático y formateo automático (no bloquean el build) |

---

## Arquitectura — Hexagonal (Ports & Adapters)

Adoptada de verdad (2026-08-10), simétrica con el módulo CV (Python).
Decisión pragmática: los records de `domain/model/` mantienen `@Table`/`@Id`
R2DBC (son anémicos, sin lógica que proteger) y los `*RepositoryPort`
extienden `ReactiveCrudRepository<T, Long>` en vez de redeclarar save/
findById a mano (Spring Data los declara genéricos — una redeclaración no
genérica choca en compilación).

```
src/main/java/com/safevision/back/
├── domain/model/            ← Entidades R2DBC (records Java 21, @Table/@Id)
├── application/
│   ├── ports/out/           ← 15 *RepositoryPort + NotificationChannelPort,
│   │                            EvidenceStoragePort, RulesPublisherPort
│   └── service/             ← Lógica de negocio (Reactor puro), depende solo de puertos
└── infrastructure/
    ├── web/                  ← Controllers + dto/ (Request/Response)
    ├── persistence/          ← 15 repos Spring Data `extends XRepositoryPort {}`
    ├── messaging/             ← MqttClientConfig, MqttIncidentSubscriber, MqttRulesPublisher
    ├── notification/          ← TelegramNotificationService
    ├── storage/               ← EvidencePresignService (S3 URLs prefirmadas)
    └── config/                 ← OpenApiConfig, SecurityConfig, *Properties
```

**IDs:** `Long` (BIGINT IDENTITY), no UUID (`incidents.external_id` es la
excepción — UUID del CV, solo para correlación, no PK).
**Reactive first:** cero `.block()`. Reactor puro, sin RxJava (nunca se usó).

---

## Entidades del Dominio

`Site`, `Zone`, `Camera`, `Worker`, `User`, `UserRole`, `SiteContact`,
`EppParameter`, `SiteEppRequirement`, `Incident`, `Evidence`, `Notification`,
`NotificationChannel`, `NotificationStatus`

---

## API Endpoints

### Datos Maestros

```
GET/POST        /api/v1/sites
GET/PUT/DELETE  /api/v1/sites/{id}
GET/POST        /api/v1/sites/{siteId}/zones
PUT/DELETE      /api/v1/sites/{siteId}/zones/{zoneId}
GET/POST        /api/v1/sites/{siteId}/contacts
PUT/DELETE      /api/v1/sites/{siteId}/contacts/{contactId}
GET/POST        /api/v1/cameras
GET/PUT/DELETE  /api/v1/cameras/{id}
GET/POST        /api/v1/workers
GET/PUT/DELETE  /api/v1/workers/{id}
GET/POST        /api/v1/users
GET/PUT/DELETE  /api/v1/users/{id}
```

`UserResponse` trae `roleCode` desde 2026-08-15 (antes solo `roleId`).
`UserRequest.password` es obligatorio también en PUT — no hay update parcial.

### Incidentes EPP (HU10)

```
POST  /api/v1/incidents          ← legacy HTTP, sin uso activo del CV (ahora publica por MQTT)
GET   /api/v1/incidents          ← filtros opcionales: siteId, workerId, from, to
GET   /api/v1/incidents/{id}
GET   /api/v1/incidents/{id}/evidence   ← foto/clip con URL de S3 prefirmada
```

### Ingesta activa (MQTT, reemplaza HTTP)

```
CV → Backend  MQTT safevision/{siteId}/incidents         ← incidente + foto ya en S3
CV → Backend  MQTT safevision/{siteId}/incidents/clips    ← clip listo (minutos despues)
Backend → CV  MQTT safevision/{siteId}/rules (retained)   ← reglas EPP, reemplaza polling HTTP
```

### Parámetros EPP (HU04)

```
GET   /api/v1/parameters/{siteId}   ← fallback al catálogo completo si la obra no tiene config
PUT   /api/v1/parameters/{siteId}
```

### Reportes (HU12, 2026-08-16)

```
GET   /api/v1/reports   ← filtros opcionales siteId/from/to, default últimos 30 días
```

`ReportController` → `ReportService` → `ReportRepositoryPort` /
`ReportQueryRepository` (único adaptador con `DatabaseClient` en vez de
`ReactiveCrudRepository` — son proyecciones de solo lectura con `GROUP BY`,
no hay entidad "reporte"). Agrega: total, por tipo EPP (`unnest`), por
obra, por zona, tendencia diaria (zero-filled), top 5 trabajadores, obra
crítica, tendencia vs. periodo anterior, salud de notificaciones Telegram.
**Sin "% de cumplimiento" a propósito** — el esquema solo registra
`incidents` (violaciones), no hay tabla de chequeos conformes con la que
calcular un denominador real.

### Formato de respuesta — ApiEnvelope<T> (nuevo, 2026-08-15)

```
Éxito: {"status":"200","datetime":"...","error":false,"data":{...}}
Error: {"status":"404","datetime":"...","error":true,"errorCode":"NOT_FOUND","errorDescription":"..."}
```

`infrastructure/web/dto/ApiEnvelope.java` (record + `wrap`/`wrapList`/`wrapVoid`
para Mono/Flux — Flux se junta a List antes de envolver, se pierde el
streaming incremental a cambio del sobre uniforme) +
`infrastructure/web/GlobalExceptionHandler.java` (`@RestControllerAdvice`,
convierte `ResponseStatusException` ya existentes sin tocarlas: errorCode =
nombre del HttpStatus, errorDescription = el mensaje). Todos los
controllers cambiaron su tipo de retorno para envolver la respuesta — los
services no se tocaron. DELETE pasó de 204 a 200 (204 no puede llevar body).

### Auth (2026-08-15)

```
POST  /api/v1/auth/login   ← PÚBLICO. {username, password} -> {token, tokenType, expiresInSeconds, username, role}
```

Todo endpoint salvo `/api/v1/auth/**`, swagger y el `POST /api/v1/incidents`
legacy exige `Authorization: Bearer <jwt>` (`SecurityConfig.jwtAuthFilter`,
`JwtService`). JWT stateless — sin refresh ni logout server-side. El claim
`role` viaja en el token pero todavía no se usa para autorización por rol.

### Documentación

```
GET   /swagger-ui.html
GET   /v3/api-docs
```

### Pendiente (sin controller aún)

```
/api/v1/notifications   ← no implementado (los eventos sí se persisten;
                            sí se cuentan agregados en GET /api/v1/reports)
```

---

### Payload POST /api/v1/incidents

```json
{
  "worker_code": 3,
  "missing_epp": ["helmet", "vest"],
  "timestamp": "2026-06-24T13:30:00",
  "camera_code": "CAM-01",
  "site_name": "Main-Site",
  "frame_b64": "<JPEG en base64>"
}
```

---

## Esquema de Base de Datos (inglés)

Tablas: `sites`, `zones`, `cameras`, `workers`, `users`, `user_roles`,
`site_contacts`, `epp_parameters`, `site_epp_requirements`, `incidents`,
`evidence`, `notifications`, `notification_channels`, `notification_statuses`.

IDs `BIGINT IDENTITY` (no UUID). Soft delete vía columna `active`. FK entre
tablas — catálogos normalizados en vez de VARCHAR inline.
Ver `docs/init-schema.sql` y CLAUDE.md para el DDL completo.

---

## Flujo de Incidente

```
CV → POST /api/v1/incidents (Bearer ALERT_SERVICE_TOKEN)
  → resuelve worker_code/camera_code/site_name → IDs
  → persiste incidents + evidence
  → resuelve contactos de la obra (site_contacts, fallback env var)
  → TelegramNotificationService (por cada contacto)
  → persiste notifications (SENT/FAILED)

Nota histórica: Hikvision ISAPI (sirena, HU09) se evaluó y se descartó del
alcance — nunca tuvo cliente HTTP real. El guardado de clips de video en S3
es la funcionalidad que se entregó en su lugar.
```

---

## Variables de Entorno

```env
SPRING_R2DBC_URL=r2dbc:postgresql://localhost:5432/safevision
SPRING_R2DBC_USERNAME=safevision
SPRING_R2DBC_PASSWORD=
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
ALERT_SERVICE_TOKEN=
JWT_SECRET=
JWT_EXPIRATION_MINUTES=480
MQTT_BROKER_HOST=
MQTT_BROKER_PORT=1883
MQTT_USERNAME=
MQTT_PASSWORD=
S3_BUCKET=
AWS_REGION=us-east-1
S3_PRESIGN_TTL_MINUTES=15
SERVER_PORT=8080
```

---

## Convenciones

- Java 21: records, sealed classes, pattern matching.
- Nombres en inglés (código y BD), comentarios en español.
- Reactive first — cero `.block()`.
- Cobertura tests >= 70% — verificado 2026-08-16: 162 tests, 0 fallos, 1 omitido (JaCoCo).
- `application/service/*` depende de interfaces (`application/ports/out/`), nunca de una clase concreta de `infrastructure/`.
- Testcontainers en `pom.xml` pero sin tests de integración que lo usen todavía.
- Endpoints documentados con `@Operation` / `@ApiResponse`.

## Git

```
feature/HU<NN>-<short-name> → develop → release → main
feat(HU10): implementa registro de incidente y notificación Telegram
feat(sites): implementa CRUD de obras
```

## Estado Actual (resumen — ver CLAUDE.md para el checklist completo)

Implementado: CRUD de Sites/Zones/Cameras/Workers/Users/SiteContacts,
arquitectura hexagonal completa (domain/application/infrastructure),
ingesta de incidentes/clips por MQTT (`MqttIncidentSubscriber`, reemplaza
el POST HTTP como camino activo), reglas EPP por MQTT retained
(`MqttRulesPublisher`, reemplaza el webhook HU04), evidencia (foto+clip) en
S3 con URLs prefirmadas (`EvidencePresignService`), endpoint
`GET /api/v1/incidents/{id}/evidence`, notificación Telegram vía URL
prefirmada o bytes inline, login JWT (`POST /api/v1/auth/login`, público) con
el resto de endpoints protegidos por `Authorization: Bearer <jwt>`, todas las
respuestas envueltas en `ApiEnvelope<T>` (DELETE ahora 200, no 204),
reportes agregados (`GET /api/v1/reports`, HU12, sin "% de cumplimiento" —
ver arriba), Swagger, 162 tests unitarios, infraestructura AWS como código
(dos stacks CloudFormation: el original HTTP y el nuevo
`safevision-stack-mqtt.yaml`).

Pendiente: endpoint de notificaciones (lectura del historial), tests de
integración con Testcontainers, autorización por rol (el claim `role` del
JWT no se valida todavía en ningún endpoint), `infra/deploy.sh`/`README.md`
actualizados para el nuevo stack MQTT+S3.
