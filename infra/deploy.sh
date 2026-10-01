#!/bin/bash
# SafeVision — un solo script para todo el ciclo de vida del stack de demo.
#
# Uso:
#   ./deploy.sh up       # build+push imagenes a ECR + sube SQL/videos a S3 + asegura el bucket de evidencia + crea el stack + espera la API + muestra los resultados
#   ./deploy.sh build    # solo build+push de las imagenes Docker a ECR (sin tocar el stack)
#   ./deploy.sh upload   # solo re-sube SQL/videos a S3 y asegura el bucket de evidencia (sin tocar el stack)
#   ./deploy.sh wire     # re-enlaza la camara del seed con la IP del CV (lo hace solo el bootstrap del backend; esto es el respaldo manual por SSH)
#   ./deploy.sh status   # muestra el estado actual del stack + sus Outputs
#   ./deploy.sh down     # borra el stack completo
#
# Configuracion: copiar infra/deploy.env.example a infra/deploy.env y completar
# ahi los valores (nunca se pasan por linea de comandos ni se commitean).

set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_REPO="$(cd "$INFRA_DIR/.." && pwd)"
ENV_FILE="$INFRA_DIR/deploy.env"
TEMPLATE="$INFRA_DIR/cloudformation/safevision-stack.yaml"

# Nombres de los repos ECR — deben coincidir con los defaults de
# EcrBackendRepoName/EcrCvRepoName en el template. Recursos persistentes
# (igual que el bucket S3), fuera del ciclo de vida del stack — para no
# forzar un re-push de varios GB cada vez que se crea/borra el stack.
ECR_BACKEND_REPO="safevision-backend"
ECR_CV_REPO="safevision-cv"

# aws.exe nativo (no la version MSYS) no resuelve una ruta POSIX de Git Bash
# embebida en un file://. cygpath -m la convierte a estilo Windows (F:/...).
if command -v cygpath >/dev/null 2>&1; then
  TEMPLATE="$(cygpath -m "$TEMPLATE")"
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Falta $ENV_FILE — copia deploy.env.example a deploy.env y completa los valores."
  exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

for var in AWS_REGION STACK_NAME ASSETS_BUCKET CV_REPO_PATH KEY_PAIR_NAME PEM_PATH \
           MY_IP_CIDR VPC_ID SUBNET_ID DB_MASTER_PASSWORD ALERT_SERVICE_TOKEN \
           TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID AWS_PROFILE; do
  if [ -z "${!var:-}" ]; then
    echo "Falta completar '$var' en $ENV_FILE"
    exit 1
  fi
done

# Opcionales con default: bucket de evidencia (foto + clip de incidentes) y
# el secreto de los JWT. Si JWT_SECRET no esta en deploy.env se genera uno por
# stack — los tokens emitidos dejan de valer al recrear el stack, nada mas.
EVIDENCE_BUCKET="${EVIDENCE_BUCKET:-${ASSETS_BUCKET}-evidence}"
if [ -z "${JWT_SECRET:-}" ]; then
  JWT_SECRET="$(openssl rand -hex 32)"
fi

# ────────────────────────────────────────────────────────────
# build — build + push de las imagenes Docker a ECR
# ────────────────────────────────────────────────────────────
do_build_and_push() {
  local cv_repo account_id registry
  cv_repo="$(cd "$INFRA_DIR/$CV_REPO_PATH" && pwd)"
  account_id=$(aws sts get-caller-identity --profile "$AWS_PROFILE" --query Account --output text)
  registry="$account_id.dkr.ecr.$AWS_REGION.amazonaws.com"

  for repo in "$ECR_BACKEND_REPO" "$ECR_CV_REPO"; do
    if ! aws ecr describe-repositories --repository-names "$repo" --profile "$AWS_PROFILE" --region "$AWS_REGION" >/dev/null 2>&1; then
      echo "Repositorio ECR '$repo' no existe, creandolo..."
      aws ecr create-repository --repository-name "$repo" --profile "$AWS_PROFILE" --region "$AWS_REGION" >/dev/null
    fi
  done

  echo "--- Login a ECR ($registry) ---"
  aws ecr get-login-password --profile "$AWS_PROFILE" --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$registry"

  echo "--- Backend: build + push ---"
  docker build -t "$registry/$ECR_BACKEND_REPO:latest" "$BACKEND_REPO"
  docker push "$registry/$ECR_BACKEND_REPO:latest"

  echo "--- CV: build + push ---"
  docker build -t "$registry/$ECR_CV_REPO:latest" "$cv_repo"
  docker push "$registry/$ECR_CV_REPO:latest"

  echo
  echo "=== Imagenes publicadas en ECR ($registry) ==="
}

# ────────────────────────────────────────────────────────────
# upload — subir SQL (schema + seed) y videos de prueba a S3
# ────────────────────────────────────────────────────────────
do_upload() {
  local cv_repo
  cv_repo="$(cd "$INFRA_DIR/$CV_REPO_PATH" && pwd)"

  if ! aws s3api head-bucket --bucket "$ASSETS_BUCKET" --profile "$AWS_PROFILE" --region "$AWS_REGION" 2>/dev/null; then
    echo "Bucket s3://$ASSETS_BUCKET no existe, creandolo..."
    aws s3 mb "s3://$ASSETS_BUCKET" --region "$AWS_REGION" --profile "$AWS_PROFILE"
  fi

  echo "--- SQL (schema + seed) ---"
  aws s3 cp "$BACKEND_REPO/docs/init-schema.sql" "s3://$ASSETS_BUCKET/sql/init-schema.sql" --profile "$AWS_PROFILE"
  aws s3 cp "$BACKEND_REPO/docs/seed-data.sql" "s3://$ASSETS_BUCKET/sql/seed-data.sql" --profile "$AWS_PROFILE"

  echo "--- Videos de prueba ---"
  for n in 1 2 3; do
    aws s3 cp "$cv_repo/tests/fixtures/video/OBRA_REAL_${n}.mp4" "s3://$ASSETS_BUCKET/videos/OBRA_REAL_${n}.mp4" --profile "$AWS_PROFILE"
  done

  do_ensure_evidence_bucket

  echo
  echo "=== Assets subidos. Contenido de s3://$ASSETS_BUCKET: ==="
  aws s3 ls "s3://$ASSETS_BUCKET" --recursive --human-readable --summarize --region "$AWS_REGION" --profile "$AWS_PROFILE"
}

# ────────────────────────────────────────────────────────────
# evidence-bucket — bucket persistente de evidencia (lo escribe el CV, lo lee
# el backend para las URLs prefirmadas). Privado, con expiracion a 60 dias.
# ────────────────────────────────────────────────────────────
do_ensure_evidence_bucket() {
  if ! aws s3api head-bucket --bucket "$EVIDENCE_BUCKET" --profile "$AWS_PROFILE" --region "$AWS_REGION" 2>/dev/null; then
    echo "Bucket de evidencia s3://$EVIDENCE_BUCKET no existe, creandolo..."
    aws s3 mb "s3://$EVIDENCE_BUCKET" --region "$AWS_REGION" --profile "$AWS_PROFILE"
  fi
  aws s3api put-public-access-block --bucket "$EVIDENCE_BUCKET" --profile "$AWS_PROFILE" --region "$AWS_REGION" \
    --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  aws s3api put-bucket-lifecycle-configuration --bucket "$EVIDENCE_BUCKET" --profile "$AWS_PROFILE" --region "$AWS_REGION" \
    --lifecycle-configuration '{"Rules":[{"ID":"expira-evidencia-60-dias","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":60}}]}'
  echo "Bucket de evidencia listo: s3://$EVIDENCE_BUCKET (privado, expira a los 60 dias)"
}

# ────────────────────────────────────────────────────────────
# create-stack + esperar + mostrar Outputs
# ────────────────────────────────────────────────────────────
do_create_stack() {
  echo "--- Creando stack '$STACK_NAME' en $AWS_REGION ---"
  aws cloudformation create-stack \
    --stack-name "$STACK_NAME" \
    --template-body "file://$TEMPLATE" \
    --capabilities CAPABILITY_IAM \
    --region "$AWS_REGION" \
    --parameters \
      ParameterKey=VpcId,ParameterValue="$VPC_ID" \
      ParameterKey=SubnetId,ParameterValue="$SUBNET_ID" \
      ParameterKey=KeyPairName,ParameterValue="$KEY_PAIR_NAME" \
      ParameterKey=MyIpCidr,ParameterValue="$MY_IP_CIDR" \
      ParameterKey=AssetsBucketName,ParameterValue="$ASSETS_BUCKET" \
      ParameterKey=EvidenceBucketName,ParameterValue="$EVIDENCE_BUCKET" \
      ParameterKey=EcrBackendRepoName,ParameterValue="$ECR_BACKEND_REPO" \
      ParameterKey=EcrCvRepoName,ParameterValue="$ECR_CV_REPO" \
      ParameterKey=DbMasterPassword,ParameterValue="$DB_MASTER_PASSWORD" \
      ParameterKey=AlertServiceToken,ParameterValue="$ALERT_SERVICE_TOKEN" \
      ParameterKey=JwtSecret,ParameterValue="$JWT_SECRET" \
      ParameterKey=TelegramBotToken,ParameterValue="$TELEGRAM_BOT_TOKEN" \
      ParameterKey=TelegramChatId,ParameterValue="$TELEGRAM_CHAT_ID" --profile "$AWS_PROFILE"

  echo "--- Esperando CREATE_COMPLETE (~10-15 min, RDS es lo que mas tarda) ---"
  aws cloudformation wait stack-create-complete --stack-name "$STACK_NAME" --region "$AWS_REGION" --profile "$AWS_PROFILE"
  echo "Stack listo."
}

# ────────────────────────────────────────────────────────────
# wait-ready — espera a que la API del backend responda (desde tu IP, que el
# security group ya permite en :8080). El enlace backend -> CV lo hace solo el
# bootstrap del backend (/opt/safevision/wire-camera.sh), sin SSH.
# ────────────────────────────────────────────────────────────
do_wait_ready() {
  local backend_ip intentos=0
  backend_ip=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --profile "$AWS_PROFILE" --region "$AWS_REGION" \
    --query 'Stacks[0].Outputs[?OutputKey==`BackendPublicIP`].OutputValue' --output text)
  echo "--- Esperando a que la API responda en http://$backend_ip:8080 (hasta ~20 min) ---"
  # spring.webflux.base-path=/api/v1 se aplica a todo, incluido springdoc — sin
  # el prefijo esta ruta da 404 aunque la API ya este arriba.
  until curl -s -o /dev/null -w "%{http_code}" "http://$backend_ip:8080/api/v1/v3/api-docs" 2>/dev/null | grep -qE "^(200|401)$"; do
    intentos=$((intentos + 1))
    if [ "$intentos" -gt 120 ]; then
      echo "La API no respondio tras ~20 min — revisar /var/log/safevision-userdata.log"
      echo "(ssh -i $PEM_PATH ubuntu@$backend_ip)"
      return 1
    fi
    if [ $((intentos % 6)) -eq 0 ]; then
      echo "  ...sigue arrancando ($((intentos * 10 / 60)) min transcurridos)"
    fi
    sleep 10
  done
  echo "API arriba: http://$backend_ip:8080/swagger-ui.html"
}

# ────────────────────────────────────────────────────────────
# wire — respaldo manual: vuelve a correr en el backend el script que guarda
# la IP privada del CV en cameras.ip_address (la usan HttpRulesPublisher /
# HttpCameraConfigPublisher para avisarle al CV). Solo hace falta si el
# bootstrap no lo logro (ver /var/log/safevision-userdata.log del backend).
# ────────────────────────────────────────────────────────────
do_wire() {
  local backend_ip
  backend_ip=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --profile "$AWS_PROFILE" --region "$AWS_REGION" \
    --query 'Stacks[0].Outputs[?OutputKey==`BackendPublicIP`].OutputValue' --output text)
  ssh -i "$PEM_PATH" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 "ubuntu@$backend_ip" \
    "sudo /opt/safevision/wire-camera.sh"
}

# ────────────────────────────────────────────────────────────
# status — estado + Outputs
# ────────────────────────────────────────────────────────────
do_status() {
  local status
  status=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --profile "$AWS_PROFILE" --region "$AWS_REGION" \
    --query 'Stacks[0].StackStatus' --output text 2>&1) || {
    echo "El stack '$STACK_NAME' no existe."
    return 1
  }
  echo "Estado: $status"
  echo
  echo "Outputs:"
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" --profile "$AWS_PROFILE" --region "$AWS_REGION" \
    --query 'Stacks[0].Outputs' --output table
}

# ────────────────────────────────────────────────────────────
# down — borrar todo
# ────────────────────────────────────────────────────────────
do_down() {
  echo "--- Borrando stack '$STACK_NAME' ---"
  aws cloudformation delete-stack --stack-name "$STACK_NAME" --profile "$AWS_PROFILE" --region "$AWS_REGION"
  echo "--- Esperando a que termine de borrarse ---"
  aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME" --profile "$AWS_PROFILE" --region "$AWS_REGION"
  echo "Stack borrado. (Los buckets S3 de assets y evidencia y los repos ECR no se tocan — quedan listos para el proximo up.)"
}

# ────────────────────────────────────────────────────────────
main() {
  case "${1:-}" in
    up)
      do_build_and_push
      do_upload
      do_create_stack
      do_wait_ready || true
      do_status
      ;;
    build)
      do_build_and_push
      ;;
    upload)
      do_upload
      ;;
    wire)
      do_wire
      ;;
    status)
      do_status
      ;;
    down)
      do_down
      ;;
    *)
      echo "Uso: $0 {up|build|upload|wire|status|down}"
      exit 1
      ;;
  esac
}

main "$@"
