#!/usr/bin/env bash
# Deploys the whole demo to Google Cloud Run. Idempotent: safe to re-run to ship new code.
#
#   Required env:  GCP_PROJECT (must have billing enabled)
#   Optional env:  ANTHROPIC_API_KEY (only read the first time, to create the secret)
#   Optional env:  REGION (us-central1), SQL_TIER (db-f1-micro), AI_MODEL
#
# Topology: three Cloud Run services (decision-service, ai-service, frontend/nginx) + Cloud SQL
# Postgres 16 on a private IP, reached through Direct VPC egress (so the apps need no Cloud SQL
# connector library). Secrets live in Secret Manager, never in the repo (CLAUDE.md rule 8).
# Run from anywhere; paths are resolved relative to the repo root.
set -euo pipefail

: "${GCP_PROJECT:?set GCP_PROJECT}"
REGION="${REGION:-us-central1}"
SQL_TIER="${SQL_TIER:-db-f1-micro}"
AI_MODEL="${AI_MODEL:-claude-sonnet-4-5-20250929}"
REPO="dmn-rules"
SQL_INSTANCE="dmn-rules-db"
DB_NAME="dmn_rules"
DB_USER="dmn_rules"
NETWORK="default"
REGISTRY="${REGION}-docker.pkg.dev/${GCP_PROJECT}/${REPO}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

gcloud config set project "$GCP_PROJECT" >/dev/null

echo "==> Enabling APIs"
gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com cloudbuild.googleapis.com servicenetworking.googleapis.com \
  compute.googleapis.com

echo "==> Artifact Registry"
gcloud artifacts repositories describe "$REPO" --location "$REGION" >/dev/null 2>&1 ||
  gcloud artifacts repositories create "$REPO" --repository-format docker --location "$REGION"

echo "==> Private services access (Cloud SQL private IP)"
gcloud compute addresses describe google-managed-services-default --global >/dev/null 2>&1 ||
  gcloud compute addresses create google-managed-services-default --global \
    --purpose VPC_PEERING --prefix-length 16 --network "$NETWORK"
gcloud services vpc-peerings list --network "$NETWORK" --format='value(peering)' | grep -q servicenetworking ||
  gcloud services vpc-peerings connect --service servicenetworking.googleapis.com \
    --ranges google-managed-services-default --network "$NETWORK"

echo "==> Cloud SQL (Postgres 16)"
if ! gcloud sql instances describe "$SQL_INSTANCE" >/dev/null 2>&1; then
  gcloud sql instances create "$SQL_INSTANCE" --database-version POSTGRES_16 --tier "$SQL_TIER" --edition ENTERPRISE \
    --region "$REGION" --network "projects/${GCP_PROJECT}/global/networks/${NETWORK}" --no-assign-ip
fi
gcloud sql databases describe "$DB_NAME" --instance "$SQL_INSTANCE" >/dev/null 2>&1 ||
  gcloud sql databases create "$DB_NAME" --instance "$SQL_INSTANCE"

echo "==> Secrets"
secret_exists() { gcloud secrets describe "$1" >/dev/null 2>&1; }
if ! secret_exists dmn-db-password; then
  # The DB user is created in the same branch, so the password and user can never drift apart.
  DB_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  printf '%s' "$DB_PASSWORD" | gcloud secrets create dmn-db-password --data-file=-
  gcloud sql users create "$DB_USER" --instance "$SQL_INSTANCE" --password "$DB_PASSWORD"
fi
if ! secret_exists dmn-anthropic-key; then
  # Without a key everything except "Ask AI" works; add the real one later with
  #   printf '%s' "$KEY" | gcloud secrets versions add dmn-anthropic-key --data-file=-
  # then redeploy ai-service (or re-run this script) so a new revision picks it up.
  [ -n "${ANTHROPIC_API_KEY:-}" ] || echo "WARNING: ANTHROPIC_API_KEY not set; storing a placeholder (Ask AI will not answer)"
  printf '%s' "${ANTHROPIC_API_KEY:-unset}" | gcloud secrets create dmn-anthropic-key --data-file=-
fi
# Admin console password and session-token signing secret. Both must be fixed values here: Cloud Run
# scales decision-service to zero, and a per-start random value would change the admin password and
# sign everyone out on every cold start. Read the admin password with:
#   gcloud secrets versions access latest --secret dmn-admin-password
for s in dmn-admin-password dmn-auth-token-secret; do
  if ! secret_exists "$s"; then
    head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32 | gcloud secrets create "$s" --data-file=-
  fi
done
# Cloud Run's default compute service account reads the secrets at startup.
PROJECT_NUMBER="$(gcloud projects describe "$GCP_PROJECT" --format='value(projectNumber)')"
RUN_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
for s in dmn-db-password dmn-anthropic-key dmn-admin-password dmn-auth-token-secret; do
  gcloud secrets add-iam-policy-binding "$s" --member "serviceAccount:${RUN_SA}" \
    --role roles/secretmanager.secretAccessor >/dev/null
done

echo "==> Building images (Cloud Build)"
SQL_IP="$(gcloud sql instances describe "$SQL_INSTANCE" --format='value(ipAddresses[0].ipAddress)')"
TAG="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || date +%s)"
build() { # <name> <dockerfile>
  local cfg; cfg="$(mktemp)"
  cat >"$cfg" <<EOF
steps:
- name: gcr.io/cloud-builders/docker
  args: ['build', '-f', '$2', '-t', '${REGISTRY}/$1:${TAG}', '.']
images: ['${REGISTRY}/$1:${TAG}']
EOF
  gcloud builds submit "$ROOT" --config "$cfg"
  rm -f "$cfg"
}
build decision-service decision-service/Dockerfile
build ai-service ai-service/Dockerfile
build frontend frontend/Dockerfile

JDBC="jdbc:postgresql://${SQL_IP}:5432/${DB_NAME}"
VPC=(--network "$NETWORK" --subnet "$NETWORK" --vpc-egress private-ranges-only)
# RESET_DEMO_ON_START=false: with Cloud Run instances starting and stopping at will, "true" would
# wipe the database on every cold start. "false" still seeds once when the database is empty.
# max-instances=1 keeps the first-run seed from racing itself.
# db-f1-micro allows only ~25 connections in total, and Spring defaults to a pool of 10 per instance
# (idle instances and rolling revisions overlap), which exhausts them and blocks new revisions from
# starting. Keep each instance to 3.
POOL="SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3,SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1"
COMMON=(--region "$REGION" --allow-unauthenticated --max-instances 1 "${VPC[@]}")

echo "==> decision-service"
gcloud run deploy decision-service --image "${REGISTRY}/decision-service:${TAG}" "${COMMON[@]}" \
  --cpu 1 --memory 1Gi --cpu-boost --port 8080 \
  --set-env-vars "SPRING_DATASOURCE_URL=${JDBC},SPRING_DATASOURCE_USERNAME=${DB_USER},RESET_DEMO_ON_START=false,${POOL}" \
  --set-secrets "SPRING_DATASOURCE_PASSWORD=dmn-db-password:latest,DEMO_ADMIN_PASSWORD=dmn-admin-password:latest,AUTH_TOKEN_SECRET=dmn-auth-token-secret:latest"
DECISION_URL="$(gcloud run services describe decision-service --region "$REGION" --format='value(status.url)')"

echo "==> ai-service"
# 4Gi: the local embedding model is loaded in-process. min-instances=0 (scale to zero) is the
# cost choice: an always-on instance is ~$39/month, idle. The price is a cold start on the first
# chat after idle (the model is baked into the image, so there is no download). Before a demo,
# keep it warm with:  gcloud run services update ai-service --region "$REGION" --min-instances 1
# and set it back to 0 afterwards.
gcloud run deploy ai-service --image "${REGISTRY}/ai-service:${TAG}" "${COMMON[@]}" \
  --cpu 2 --memory 4Gi --cpu-boost --min-instances 0 --port 8081 --timeout 300 \
  --set-env-vars "SPRING_DATASOURCE_URL=${JDBC},SPRING_DATASOURCE_USERNAME=${DB_USER},RESET_DEMO_ON_START=false,${POOL},DECISION_SERVICE_URL=${DECISION_URL},AI_PROVIDER=anthropic,AI_MODEL=${AI_MODEL}" \
  --set-secrets "SPRING_DATASOURCE_PASSWORD=dmn-db-password:latest,ANTHROPIC_API_KEY=dmn-anthropic-key:latest"
AI_URL="$(gcloud run services describe ai-service --region "$REGION" --format='value(status.url)')"

echo "==> frontend"
# These five values fill deploy/nginx/nginx.conf.template. The *_HOST values are the bare
# hostnames, used as the Host header so Cloud Run routes the proxied request correctly.
# PORT is injected by Cloud Run itself, so it is deliberately not set here.
gcloud run deploy frontend --image "${REGISTRY}/frontend:${TAG}" --region "$REGION" --allow-unauthenticated \
  --cpu 1 --memory 256Mi --port 8080 \
  --set-env-vars "^|^DECISION_SERVICE_URL=${DECISION_URL}|DECISION_SERVICE_HOST=${DECISION_URL#https://}|AI_SERVICE_URL=${AI_URL}|AI_SERVICE_HOST=${AI_URL#https://}"
FRONTEND_URL="$(gcloud run services describe frontend --region "$REGION" --format='value(status.url)')"

echo
echo "Deployed: ${FRONTEND_URL}"
