#!/bin/sh
# One-time setup of a Google Cloud project for the White Box mirror (Cloud Run + Firestore in Seoul).
#
#     PROJECT=<project id> server/deploy/setup.sh
#
# Turns on the services it needs, creates the Firestore database (native mode, asia-northeast3), the bucket for saved
# clips, the two secrets (the server's keys, made here once; the dashboard password, asked for), and lets the Cloud Run
# service account use them. Safe to run again: what exists is left as it is.
set -e
PROJECT=${PROJECT:-$(gcloud config get-value project 2>/dev/null)}
REGION=asia-northeast3
BUCKET="$PROJECT-whitebox-clips"
[ -n "$PROJECT" ] || { echo "set PROJECT or run: gcloud config set project <id>"; exit 1; }
cd "$(dirname "$0")/../.."

gcloud services enable run.googleapis.com firestore.googleapis.com secretmanager.googleapis.com \
  storage.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com --project "$PROJECT"

gcloud firestore databases describe --database="(default)" --project "$PROJECT" >/dev/null 2>&1 ||
  gcloud firestore databases create --location="$REGION" --type=firestore-native --project "$PROJECT"

gcloud storage buckets describe "gs://$BUCKET" --project "$PROJECT" >/dev/null 2>&1 ||
  gcloud storage buckets create "gs://$BUCKET" --location="$REGION" --uniform-bucket-level-access --project "$PROJECT"

if ! gcloud secrets describe whitebox-server-key --project "$PROJECT" >/dev/null 2>&1; then
  ./gradlew -q :server:newServerKey | gcloud secrets create whitebox-server-key --data-file=- --project "$PROJECT"
fi
if ! gcloud secrets describe whitebox-dashboard-password --project "$PROJECT" >/dev/null 2>&1; then
  printf "Dashboard password (judges will need it): "
  stty -echo; read -r PASSWORD; stty echo; echo
  printf %s "$PASSWORD" | gcloud secrets create whitebox-dashboard-password --data-file=- --project "$PROJECT"
fi

# Cloud Run runs as the default compute service account unless told otherwise.
NUMBER=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
SA="$NUMBER-compute@developer.gserviceaccount.com"
gcloud projects add-iam-policy-binding "$PROJECT" --member="serviceAccount:$SA" --role=roles/datastore.user --condition=None >/dev/null
for s in whitebox-server-key whitebox-dashboard-password; do
  gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:$SA" --role=roles/secretmanager.secretAccessor --project "$PROJECT" >/dev/null
done
gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" --member="serviceAccount:$SA" --role=roles/storage.objectAdmin >/dev/null
echo "ready: PROJECT=$PROJECT server/deploy/deploy.sh"
