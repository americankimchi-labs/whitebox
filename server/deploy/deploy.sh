#!/bin/sh
# Builds the mirror server and deploys it to Cloud Run in Seoul (after setup.sh).
#
#     PROJECT=<project id> server/deploy/deploy.sh
#
# One instance at most, so the dead-man timers and the in-memory indexes agree; CPU stays allocated so the timers run
# while no car is connected (a crashed car's silence is exactly when they must fire); 3600 s requests, as long as Cloud
# Run allows, and the car reconnects at once when a connection ends. WHITEBOX_APP_VERSION names the newest app version
# the cars are told about: change it with each release.
set -e
PROJECT=${PROJECT:-$(gcloud config get-value project 2>/dev/null)}
REGION=asia-northeast3
APP_VERSION=${APP_VERSION:-2|0.9.0}
[ -n "$PROJECT" ] || { echo "set PROJECT or run: gcloud config set project <id>"; exit 1; }
cd "$(dirname "$0")/../.."
./gradlew -q :server:installDist
cp server/deploy/Dockerfile server/Dockerfile
trap 'rm -f server/Dockerfile' EXIT
gcloud run deploy whitebox --source server --region "$REGION" --project "$PROJECT" \
  --allow-unauthenticated --execution-environment gen2 \
  --min-instances 0 --max-instances 1 --no-cpu-throttling --timeout 3600 --memory 1Gi --cpu 1 \
  --set-env-vars "^@^WHITEBOX_STORE=firestore@WHITEBOX_CLIPS=/clips@WHITEBOX_APP_VERSION=$APP_VERSION" \
  --set-secrets WHITEBOX_SERVER_KEY=whitebox-server-key:latest,WHITEBOX_DASHBOARD_PASSWORD=whitebox-dashboard-password:latest \
  --add-volume name=clips,type=cloud-storage,bucket="$PROJECT-whitebox-clips" --add-volume-mount volume=clips,mount-path=/clips
URL=$(gcloud run services describe whitebox --region "$REGION" --project "$PROJECT" --format='value(status.url)')
echo "deployed: $URL"
echo "the car's mirror (whitebox.mirrorUrl.release in local.properties): wss://${URL#https://}/v1/stream"
