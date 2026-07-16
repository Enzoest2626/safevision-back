# SafeVision — Infraestructura como código (CloudFormation)

Levanta todo el entorno de demo (RDS + 2 EC2, ya configuradas y con el código
corriendo) con un solo comando. Sin SSH manual, sin instalar nada a mano — el
bootstrap completo vive en `UserData` dentro del template: instala Docker y
hace `docker pull` de las imágenes ya publicadas en ECR.

Dos capas con ciclos de vida distintos:
- **Persistente** (no se borra con `down`): el bucket S3 (SQL + videos) y los
  2 repos ECR (imágenes de backend y CV) — evita tener que resubir varios GB
  cada vez que se recrea el stack.
- **Efímero** (se crea/borra por sesión): el stack de CloudFormation (RDS +
  2 EC2 + Security Groups + IAM). Pensado para crear y borrar por sesión
  (ensayo, demo real) — no para dejarlo prendido todo el tiempo.

## Prerequisitos (una sola vez)

1. **Docker Desktop / Rancher Desktop corriendo** en tu máquina — `deploy.sh
   build` compila las imágenes del backend y del CV localmente antes de
   subirlas a ECR.

2. **AWS CLI configurado con un profile nombrado** (no el default):
   ```bash
   aws configure --profile safevision-demo
   # pide Access Key ID, Secret Access Key, region (us-east-1), output format
   ```
   `deploy.sh` exige `AWS_PROFILE` en `deploy.env` y lo usa en cada comando —
   no cae al profile "default" solo. Todo lo de este README corre contra tu
   cuenta real de AWS — nada de LocalStack acá.

4. **Key pair EC2** (si no tienes una todavía):
   ```bash
   aws ec2 create-key-pair --key-name safevision-demo --region us-east-1 \
     --query 'KeyMaterial' --output text > safevision-demo.pem
   chmod 400 safevision-demo.pem
   ```

5. **VPC y subnet** (la VPC default de tu cuenta sirve):
   ```bash
   aws ec2 describe-vpcs --filters Name=is-default,Values=true \
     --query 'Vpcs[0].VpcId' --output text --region us-east-1

   aws ec2 describe-subnets --filters Name=vpc-id,Values=<EL_VPC_ID_DE_ARRIBA> \
     --query 'Subnets[0].SubnetId' --output text --region us-east-1
   ```

6. **Tu IP pública** en formato CIDR:
   ```bash
   curl ifconfig.me
   # anota el resultado y agrégale /32, ej: 190.1.2.3/32
   ```

7. **Configurar `infra/deploy.env`**:
   ```bash
   cd infra
   cp deploy.env.example deploy.env
   ```
   Completa ahí todos los valores de arriba, más el nombre del bucket S3
   (se crea solo si no existe), el password de RDS, el `ALERT_SERVICE_TOKEN`
   (generalo con `python -c "import secrets; print(secrets.token_hex(32))"`)
   y tus credenciales de Telegram. **`deploy.env` nunca se commitea** (ya
   está en `.gitignore` vía `*.env`).

## Uso — un solo script

```bash
cd infra
./deploy.sh up       # build+push imagenes a ECR + sube SQL/videos a S3 + crea el stack + conecta el webhook CV<->backend + muestra los resultados
./deploy.sh status   # ver estado / Outputs en cualquier momento
./deploy.sh build    # solo build+push de las imagenes Docker a ECR (después de corregir código, sin tocar el stack)
./deploy.sh upload   # solo re-sube SQL/videos a S3 (sin tocar el stack)
./deploy.sh wire     # solo re-conecta el webhook (si ese paso automático de 'up' falló)
./deploy.sh down     # borra el stack (RDS + 2 EC2) — el bucket S3 y los repos ECR no se tocan
```

`up` hace todo de punta a punta: compila y sube las imágenes Docker del
backend y del CV a ECR, sube SQL (schema+seed) y los videos de prueba a S3,
crea el stack, espera a que termine (~10-15 min, RDS es lo que más tarda),
le avisa al backend dónde está el CV (para el webhook de recarga de
parámetros, HU04 — ver más abajo) y muestra las IPs / endpoint / comandos
SSH al final. Cada EC2 arranca instalando Docker y haciendo `docker pull` de
su imagen — no compila nada en el momento del boot.

### El webhook backend → CV (recarga de parámetros en caliente)

Cuando cambian las reglas EPP de una obra (`PUT /api/v1/parameters/{siteId}`),
el backend le avisa al CV por HTTP para que recargue al instante, sin esperar
el próximo ciclo de polling (60s). La dirección del CV (`CV_RELOAD_URL`) no
se puede conocer *antes* de crear el stack — su IP privada no existe hasta
que esa instancia arranca de verdad — por eso `up` hace esto en dos tiempos:
crea el stack completo primero, y **recién después** (`do_wire_cv_callback`
dentro de `deploy.sh`) le escribe esa dirección al backend por SSH y reinicia
el servicio. Es automático, parte de `up` — no hace falta correrlo a mano
salvo que ese paso puntual falle (ahí usás `./deploy.sh wire`).

Si el webhook no llega a conectarse por algún motivo, no rompe nada: el CV
sigue sincronizando solo, cada 60s, vía polling — el webhook es una
optimización de latencia, no una dependencia dura.

Si corregís algo en el código antes del jueves: `./deploy.sh build` compila y
sube la imagen nueva a ECR — el **próximo** `up` (o un `down` + `up`) la toma
automáticamente al hacer `docker pull`, sin tocar el template. Si solo
cambiaste el SQL o los videos, `./deploy.sh upload` alcanza.

## Verificar que todo levantó solo

```bash
# Backend — Swagger debe responder
curl -s -o /dev/null -w "%{http_code}\n" http://<IP_BACKEND>:8080/swagger-ui.html

# SSH a cualquiera de las dos, para chequear los servicios
ssh -i safevision-demo.pem ubuntu@<IP_BACKEND> "sudo systemctl status safevision-back --no-pager"
ssh -i safevision-demo.pem ubuntu@<IP_CV> "sudo systemctl status mediamtx safevision-cv --no-pager"
```

`safevision-cv` va a verse "reintentando conexión" hasta que se publique un
video (paso siguiente) — es el comportamiento esperado, no un error.

Si algo no arrancó bien, el log completo del bootstrap queda en la propia
instancia:

```bash
ssh -i safevision-demo.pem ubuntu@<IP> "cat /var/log/safevision-userdata.log"
```

## Smoke test (antes de la demo real)

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST http://<IP_BACKEND>:8080/api/v1/incidents \
  -H "Authorization: Bearer <ALERT_SERVICE_TOKEN de deploy.env>" \
  -H "Content-Type: application/json" \
  -d '{
    "worker_code": 3,
    "missing_epp": ["casco"],
    "timestamp": "2026-07-16T18:00:00",
    "camera_code": "CAM-01",
    "site_name": "Obra-Principal",
    "frame_b64": "'"$(printf 'test' | base64)"'"
  }'
```

Debe responder `201` y llegar una foto a Telegram.

## Disparar la demo

```bash
ssh -i safevision-demo.pem ubuntu@<IP_CV>
./simulate_camera.sh obra1   # o obra2 / obra3
```

Publica el video una vez en tiempo real; `safevision-cv` (que ya estaba
esperando) lo detecta solo y empieza a mandar alertas.

## Borrar todo al terminar

```bash
./deploy.sh down
```

Borra RDS, las 2 EC2, Security Groups, IAM Role/InstanceProfile y las 2
Elastic IP — sin recursos huérfanos. El bucket S3 y los 2 repos ECR **no se
borran** (quedan listos para el próximo `up`; storage de unos pocos GB,
costo insignificante).
