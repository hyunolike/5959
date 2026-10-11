#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TF_DIR="$ROOT/infra/gcp/terraform"
project="$(terraform -chdir="$TF_DIR" output -raw project_id)"
secret_dir="$ROOT/infra/gcp/secrets"

for pair in 'ogu-api-env:api-env.json' 'ogu-bff-key:bff-key' 'ogu-oauth-state:oauth-state'; do
  name="${pair%%:*}"
  file="$secret_dir/${pair#*:}"
  [[ -s "$file" ]] || { echo "Missing private file: $file" >&2; exit 1; }
done
for pair in 'ogu-api-env:api-env.json' 'ogu-bff-key:bff-key' 'ogu-oauth-state:oauth-state'; do
  name="${pair%%:*}"
  file="$secret_dir/${pair#*:}"
  version="$(gcloud secrets versions list "$name" --project="$project" --filter='state=ENABLED' --limit=1 --format='value(name)')"
  if [[ -n "$version" ]]; then
    echo "$name already has an enabled version; leaving it unchanged."
  else
    gcloud secrets versions add "$name" --project="$project" --data-file="$file" >/dev/null
    echo "$name uploaded."
  fi
done
