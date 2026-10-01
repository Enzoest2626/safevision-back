# ALCANCE del proyecto SafeVision — fuente única de límites

> Acuerdos con el PO. Yo (orquestador) y cada agente debemos respetar este
> archivo antes de proponer o implementar cualquier funcionalidad.
> Si un criterio de aceptación o una idea lo contradice, el criterio se
> renegocia con el PO — no se implementa en silencio.

## Turnos operativos: solo mañana y tarde

- El sistema opera en turnos de **mañana y tarde**. El turno **noche está
  excluido**: la degradación de la imagen no permite un análisis confiable.
- Toda reportería por turnos es **mañana/tarde únicamente**. Nada de turno
  noche en dashboards, agregados ni scheduler.

## Prohibido identificar a la persona infractora

- Sin reconocimiento facial ni biometría (el sistema usa `worker_code`, no identidad).
- Sin fichas por trabajador, sin rankings individualizados, sin drill-down por
  persona en reportes. Los agregados son anónimos: por zona, cámara, turno u obra.
- Enfoque **preventivo**, nunca punitivo ni de evaluación de desempeño individual.
- NOTA ABIERTA: el endpoint actual trae `topWorkers` por worker — entra en
  tensión con este alcance. En el MVP no se expone drill-down por persona;
  pendiente con el PO si se degrada a agregado anónimo.

## Solo seguridad ocupacional

- El sistema es exclusivamente para seguridad ocupacional — no para medir
  productividad ni desempeño individual.

## Privacidad

- Blur de rostros activado por defecto (`BLUR_FACES=true`).
- Solo se almacena evidencia de incumplimiento. Retención máxima: 30 días.

## Fuera de alcance confirmado

- HU09 alerta sonora en cámara (descartada por el PO).
- MQTT / broker (arquitectura retirada; solo queda nota histórica).
- Turno noche, identificación personal, TRIR/DART/LTIFR, reconocimiento facial.

## MVP de reportería acordado (P1, sin identificación)

Heatmap zona×hora (mañana/tarde), hora/día pico, obra crítica explicada,
comparativa de periodos, salud de notificaciones accionable, filtros + exports,
vista últimas 24h. Sin ficha por trabajador.

## Decisiones pendientes con el PO

- HU06: umbral en segundos (0.5s) vs "3 frames" literales.
- `topWorkers` existente (ver NOTA ABIERTA arriba).
