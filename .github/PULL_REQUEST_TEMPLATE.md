## HU / objetivo
<!-- Ej: HU10 — registro de incidente y notificación Telegram -->

## Qué cambia
<!-- Capas tocadas: domain / application / infrastructure -->

## Cómo se probó
- [ ] `./mvnw -B verify` en verde
- [ ] Cobertura JaCoCo >= 70% LINE / >= 60% BRANCH
- [ ] `spotless:check` + `checkstyle` + `spotbugs` en verde

## Riesgos
<!-- ApiEnvelope, SecurityConfig (JWT vs ALERT_SERVICE_TOKEN), R2DBC cero .block(), code inmutable, soft delete active -->

## Checklist
- [ ] Título sigue `feat(HUxx): ...` / `fix(...): ...` / `docs(...): ...`
- [ ] Sin secretos (`.env`, `*.pem`, tokens) ni `Co-authored-by`/menciones a IA
- [ ] Documentación de arquitectura actualizada si cambia API/esquema/env
