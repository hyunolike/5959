#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TF_DIR="$ROOT/infra/gcp/terraform"
project="$(terraform -chdir="$TF_DIR" output -raw project_id)"
zone="$(terraform -chdir="$TF_DIR" output -raw zone)"
vm="$(terraform -chdir="$TF_DIR" output -raw vm_name)"

# Refresh non-secret scripts from current metadata, then start the service.
# Bootstrap uses a lock and does not format an existing filesystem.
gcloud compute ssh "$vm" --project="$project" --zone="$zone" --tunnel-through-iap \
  --command='sudo google_metadata_script_runner startup'
