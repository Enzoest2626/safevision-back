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
| RxJava            | 3.x — lógica de negocio                       |
| R2DBC             | Driver reactivo PostgreSQL                    |
| SpringDoc OpenAPI | Swagger UI `/swagger-ui.html`                 |
| Telegram Bot API  | Notificaciones push al supervisor             |
| Hikvision ISAPI   | Activación de sirena física                   |
| Maven             | Build tool                                     |

---

## Arquitectura Hexagonal

```
src/main/java/com/safevision/back/
├── adapter/
│   ├── in/web/              ← Controllers WebFlux
│   └── out/
│       ├── persistence/     ← Repositorios R2DBC
│       ├── telegram/        ← Telegram adapter
│       └── hikvision/       ← Hikvision ISAPI adapter
├── application/service/     ← Casos de uso RxJava
├── domain/
│   ├── model/               ← Entidades (records Java 21)
│   └── port/
│       ├── in/              ← Interfaces de entrada
│       └── out/             ← Interfaces de salida
└── config/                  ← OpenApi, Security, R2DBC, Telegram, Hikvision
```

**Regla:** dominio no depende de Spring ni adapters.
**Mezcla WebFlux + RxJava:** controllers Mono/Flux, services RxJava.
Conversión siempre con `RxJava3Adapter`. Nunca `.block()`.

---

## Entidades del Dominio

`Site`, `Camera`, `Worker`, `User`, `Incident`, `Evidence`, `Notification`, `EppParameter`

---

## API Endpoints

### Datos Maestros

```
GET/POST        /api/v1/sites
GET/PUT/DELETE  /api/v1/sites/{id}
GET/POST        /api/v1/cameras
GET/PUT/DELETE  /api/v1/cameras/{id}
GET/POST        /api/v1/workers
GET/PUT/DELETE  /api/v1/workers/{id}
GET/POST        /api/v1/users
GET/PUT/DELETE  /api/v1/users/{id}
```

### Incidentes EPP (HU10)

```
POST  /api/v1/incidents       ← desde módulo CV (Bearer token)
GET   /api/v1/incidents
GET   /api/v1/incidents/{id}
```

### Reportes

```
GET   /api/v1/reports/incidents
GET   /api/v1/reports/summary
```

### Notificaciones

```
GET   /api/v1/notifications
```

### Parámetros EPP (HU04)

```
GET   /api/v1/parameters
PUT   /api/v1/parameters
```

### Documentación

```
GET   /swagger-ui.html
GET   /v3/api-docs
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

Tablas: `sites`, `cameras`, `workers`, `users`, `incidents`, `evidence`,
`notifications`, `epp_parameters`.

Soft delete vía columna `active`. FK entre tablas.
Ver CLAUDE.md para DDL completo.

---

## Flujo de Incidente

```
CV → POST /api/v1/incidents
  → persiste incidents + evidence
  → TelegramNotificationAdapter
  → HikvisionSirenAdapter
  → persiste notifications (SENT/FAILED)
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
- Cobertura tests >= 70%. Testcontainers para integración.
- Endpoints documentados con `@Operation` / `@ApiResponse`.

## Git

```
feature/HU<NN>-<short-name> → develop → release → main
feat(HU10): implementa registro de incidente y notificación Telegram
feat(sites): implementa CRUD de obras
```
