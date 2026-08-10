# AGENTS.md — SafeVision Backend

> Archivo de contexto para Codex y agentes de IA.
> Equivalente a CLAUDE.md — mantener ambos sincronizados.

---

## Descripción del Proyecto

Backend principal del sistema SafeVision: plataforma de monitoreo EPP en obras.
Recibe eventos desde el módulo de Computer Vision (Python/YOLOv11), gestiona datos
maestros (obras, cámaras, trabajadores, usuarios), genera reportes y envía
notificaciones vía Telegram y sirenas Hikvision ISAPI.

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
| Hikvision ISAPI   | Config lista (`HikvisionProperties`), cliente HTTP pendiente (HU09) |
| Maven             | Build tool                                     |
| JaCoCo / Checkstyle / SpotBugs / Spotless | Cobertura, análisis estático y formateo automático (no bloquean el build) |

---

## Arquitectura (capas planas, NO hexagonal)

```
src/main/java/com/safevision/back/
├── controller/     ← REST controllers WebFlux (Mono/Flux)
├── dto/            ← Request/Response records
├── model/          ← Entidades R2DBC (records Java 21, @Table/@Id)
├── repository/     ← ReactiveCrudRepository<T, Long>
├── service/        ← Lógica de negocio (Reactor puro, sin RxJava)
└── config/         ← OpenApiConfig, SecurityConfig, TelegramProperties, HikvisionProperties
```

**IDs:** `Long` (BIGINT IDENTITY), no UUID.
**Reactive first:** cero `.block()`. Nunca hubo capa RxJava/ports-adapters — es
código plano controller → service → repository.

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

### Incidentes EPP (HU10)

```
POST  /api/v1/incidents       ← desde módulo CV (único endpoint con Bearer token)
GET   /api/v1/incidents       ← filtros opcionales: siteId, workerId, from, to
GET   /api/v1/incidents/{id}
```

### Parámetros EPP (HU04)

```
GET   /api/v1/parameters/{siteId}   ← fallback al catálogo completo si la obra no tiene config
PUT   /api/v1/parameters/{siteId}
```

### Documentación

```
GET   /swagger-ui.html
GET   /v3/api-docs
```

### Pendiente (sin controller aún)

```
/api/v1/reports/...     ← HU12, no implementado
/api/v1/notifications   ← no implementado (los eventos sí se persisten)
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

Hikvision (HU09) aún no está integrado — solo hay config (HikvisionProperties).
```

---

## Variables de Entorno

```env
SPRING_R2DBC_URL=r2dbc:postgresql://localhost:5432/safevision
SPRING_R2DBC_USERNAME=safevision
SPRING_R2DBC_PASSWORD=
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
HIKVISION_BASE_URL=
HIKVISION_USERNAME=
HIKVISION_PASSWORD=
ALERT_SERVICE_TOKEN=
SERVER_PORT=8080
```

---

## Convenciones

- Java 21: records, sealed classes, pattern matching.
- Nombres en inglés (código y BD), comentarios en español.
- Reactive first — cero `.block()`.
- Cobertura tests >= 70% — verificado 2026-07-19: 122 tests, 0 fallos, 1 omitido, 94.6% instrucciones (JaCoCo).
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
POST+GET /api/v1/incidents (HU10), GET/PUT /api/v1/parameters/{siteId} (HU04),
notificación Telegram vía `TelegramNotificationService` (delegada desde
`IncidentService` a `IncidentNotificationService`), Swagger, Bearer token
en el endpoint de incidentes, 122 tests unitarios, infraestructura AWS como
código (CloudFormation, RDS + EC2 con imágenes Docker vía ECR).

Pendiente: reportes/estadísticas (HU12), endpoint de notificaciones, cliente
Hikvision ISAPI (HU09), tests de integración con Testcontainers, auth de
usuario/JWT para el resto de endpoints.
