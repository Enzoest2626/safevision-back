#!/bin/bash
# SafeVision — un solo script para todo el ciclo de vida del stack de demo.
#
# Uso:
#   ./deploy.sh up       # sube assets a S3 + crea el stack + conecta el webhook CV->backend + muestra los resultados
#   ./deploy.sh upload   # solo re-sube assets a S3 (build local, sin tocar el stack)
#   ./deploy.sh wire     # solo re-conecta el webhook (por si el paso automatico de 'up' fallo)
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

if [ ! -f "$ENV_FILE" ]; then
  echo "Falta $ENV_FILE — copia deploy.env.example a deploy.env y completa los valores."
  exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

for var in AWS_REGION STACK_NAME ASSETS_BUCKET CV_REPO_PATH KEY_PAIR_NAME PEM_PATH \
           MY_IP_CIDR VPC_ID SUBNET_ID DB_MASTER_PASSWORD ALERT_SERVICE_TOKEN \
           TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID; do
  if [ -z "${!var:-}" ]; then
    echo "Falta completar '$var' en $ENV_FILE"
    exit 1
  fi
done

# ────────────────────────────────────────────────────────────
# upload — build + subir assets a S3
# ────────────────────────────────────────────────────────────
do_upload() {
  local cv_repo
  cv_repo="$(cd "$INFRA_DIR/$CV_REPO_PATH" && pwd)"

  if ! aws s3api head-bucket --bucket "$ASSETS_BUCKET" --region "$AWS_REGION" 2>/dev/null; then
    echo "Bucket s3://$ASSETS_BUCKET no existe, creandolo..."
    aws s3 mb "s3://$ASSETS_BUCKET" --region "$AWS_REGION"
  fi

  echo "--- Backend JAR ---"
  (cd "$BACKEND_REPO" && ./mvnw -q clean package -DskipTests)
  local jar_path
  jar_path=$(find "$BACKEND_REPO/target" -maxdepth 1 -name "*.jar" ! -name "*.original" | head -1)
  aws s3 cp "$jar_path" "s3://$ASSETS_BUCKET/backend/app.jar"

  echo "--- SQL (schema + seed) ---"
  aws s3 cp "$BACKEND_REPO/docs/init-schema.sql" "s3://$ASSETS_BUCKET/sql/init-schema.sql"
  aws s3 cp "$BACKEND_REPO/docs/seed-data.sql" "s3://$ASSETS_BUCKET/sql/seed-data.sql"

  echo "--- Codigo del modulo CV ---"
  aws s3 sync "$cv_repo/src" "s3://$ASSETS_BUCKET/cv-app/src" --delete
  aws s3 cp "$cv_repo/main.py" "s3://$ASSETS_BUCKET/cv-app/main.py"
  aws s3 cp "$cv_repo/requirements.txt" "s3://$ASSETS_BUCKET/cv-app/requirements.txt"

  echo "--- Modelos YOLO ---"
  aws s3 cp "$cv_repo/models/yolov11s/exp2/weights/best.pt" "s3://$ASSETS_BUCKET/models/best.pt"
  aws s3 sync "$cv_repo/models/yolov11s/exp2/weights/best_int8_openvino_model" \
    "s3://$ASSETS_BUCKET/models/best_int8_openvino_model" --delete

  echo "--- Videos de prueba ---"
  for n in 1 2 3; do
    aws s3 cp "$cv_repo/tests/fixtures/video/OBRA_REAL_${n}.mp4" "s3://$ASSETS_BUCKET/videos/OBRA_REAL_${n}.mp4"
  done

  echo
  echo "=== Assets subidos. Contenido de s3://$ASSETS_BUCKET: ==="
  aws s3 ls "s3://$ASSETS_BUCKET" --recursive --human-readable --summarize --region "$AWS_REGION"
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
      ParameterKey=DbMasterPassword,ParameterValue="$DB_MASTER_PASSWORD" \
      ParameterKey=AlertServiceToken,ParameterValue="$ALERT_SERVICE_TOKEN" \
      ParameterKey=TelegramBotToken,ParameterValue="$TELEGRAM_BOT_TOKEN" \
      ParameterKey=TelegramChatId,ParameterValue="$TELEGRAM_CHAT_ID"

  echo "--- Esperando CREATE_COMPLETE (~10-15 min, RDS es lo que mas tarda) ---"
  aws cloudformation wait stack-create-complete --stack-name "$STACK_NAME" --region "$AWS_REGION"
  echo "Stack listo."
}

# ────────────────────────────────────────────────────────────
# wire-cv-callback — le dice al backend donde esta el CV, despues de
# create-stack (no se puede saber antes: la IP privada del CV no existe
# hasta que esa instancia arranca de verdad — ver infra/README.md).
# ────────────────────────────────────────────────────────────
do_wire_cv_callback() {
  local backend_ip cv_private_ip

  backend_ip=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$AWS_REGION" \
    --query 'Stacks[0].Outputs[?OutputKey==`BackendPublicIP`].OutputValue' --output text)
  cv_private_ip=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$AWS_REGION" \
    --query 'Stacks[0].Outputs[?OutputKey==`CVPrivateIP`].OutputValue' --output text)

  if [ -z "$backend_ip" ] || [ -z "$cv_private_ip" ]; then
    echo "No se pudieron leer los Outputs del stack — omito el wiring del webhook."
    return 1
  fi

  local ssh_opts=(-i "$PEM_PATH" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10)

  echo "--- Esperando a que el backend termine su bootstrap (systemd activo) ---"
  local intentos=0
  until ssh "${ssh_opts[@]}" "ubuntu@$backend_ip" "systemctl is-active --quiet safevision-back" 2>/dev/null; do
    intentos=$((intentos + 1))
    if [ "$intentos" -gt 60 ]; then
      echo "El backend no terminó de arrancar tras ~10 min — revisá /var/log/safevision-userdata.log a mano"
      echo "(ssh -i $PEM_PATH ubuntu@$backend_ip) y despues corré: ./deploy.sh wire"
      return 1
    fi
    sleep 10
  done

  echo "--- Configurando CV_RELOAD_URL=http://$cv_private_ip:8001/reload-params en el backend ---"
  ssh "${ssh_opts[@]}" "ubuntu@$backend_ip" \
    "echo 'CV_RELOAD_URL=http://$cv_private_ip:8001/reload-params' | sudo tee -a /etc/safevision/backend.env >/dev/null && sudo systemctl restart safevision-back"
  echo "Listo — el backend ahora le avisa al CV cuando cambian los parámetros EPP."
}

# ────────────────────────────────────────────────────────────
# status — estado + Outputs
# ────────────────────────────────────────────────────────────
do_status() {
  local status
  status=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$AWS_REGION" \
    --query 'Stacks[0].StackStatus' --output text 2>&1) || {
    echo "El stack '$STACK_NAME' no existe."
    return 1
  }
  echo "Estado: $status"
  echo
  echo "Outputs:"
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$AWS_REGION" \
    --query 'Stacks[0].Outputs' --output table
}

# ────────────────────────────────────────────────────────────
# down — borrar todo
# ────────────────────────────────────────────────────────────
do_down() {
  echo "--- Borrando stack '$STACK_NAME' ---"
  aws cloudformation delete-stack --stack-name "$STACK_NAME" --region "$AWS_REGION"
  echo "--- Esperando a que termine de borrarse ---"
  aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME" --region "$AWS_REGION"
  echo "Stack borrado. (El bucket S3 de assets no se toca — queda listo para el proximo create-stack.)"
}

# ────────────────────────────────────────────────────────────
main() {
  case "${1:-}" in
    up)
      do_upload
      do_create_stack
      do_wire_cv_callback
      do_status
      ;;
    upload)
      do_upload
      ;;
    wire)
      do_wire_cv_callback
      ;;
    status)
      do_status
      ;;
    down)
      do_down
      ;;
    *)
      echo "Uso: $0 {up|upload|wire|status|down}"
      exit 1
      ;;
  esac
}

main "$@"
