#!/bin/sh
# Deletes everything the mirror has stored in the cloud (after testing or a demo), so nothing accumulates:
#
#     PROJECT=<project id> server/deploy/clear.sh
#
# Removes every record log and labeled window from Firestore and every saved clip from the bucket, then starts a fresh
# revision so the running server forgets what it held in memory. The service, the database, the bucket and the secrets
# stay, so the server keeps its keys and the next car connects as before. It cannot be undone; it asks for the project
# id first (or set CONFIRM=<project id>). Disconnect the cars first: a connected car sends its records again.
set -e
PROJECT=${PROJECT:-$(gcloud config get-value project 2>/dev/null)}
REGION=asia-northeast3
BUCKET="$PROJECT-whitebox-clips"
[ -n "$PROJECT" ] || { echo "set PROJECT or run: gcloud config set project <id>"; exit 1; }
if [ "$CONFIRM" != "$PROJECT" ]; then
  printf "Deletes all White Box records and clips in %s. Type the project id to go on: " "$PROJECT"
  read -r CONFIRM
  [ "$CONFIRM" = "$PROJECT" ] || { echo "not cleared"; exit 1; }
fi

gcloud firestore bulk-delete --collection-ids=logs,chunks,docs --database="(default)" --project "$PROJECT" --quiet
if gcloud storage ls "gs://$BUCKET/**" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud storage rm "gs://$BUCKET/**" --project "$PROJECT" --quiet
fi
gcloud run services update whitebox --region "$REGION" --project "$PROJECT" \
  --update-labels "cleared=$(date -u +%Y%m%d-%H%M%S)" --quiet >/dev/null
echo "cleared: Firestore and clips are empty, and the server restarted"
