#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TF_DIR="$ROOT/infra/gcp/terraform"
project="$(terraform -chdir="$TF_DIR" output -raw project_id)"
region="$(terraform -chdir="$TF_DIR" output -raw region)"
registry="$(terraform -chdir="$TF_DIR" output -raw registry)"
account="$(terraform -chdir="$TF_DIR" output -raw build_account)"
bucket="$(terraform -chdir="$TF_DIR" output -raw build_bucket)"
release="$(git -C "$ROOT" rev-parse --short HEAD)-$(date -u +%Y%m%d%H%M%S)"

gcloud builds submit "$ROOT" --project="$project" --region="$region" \
  --config="$ROOT/infra/gcp/cloudbuild.yaml" \
  --service-account="projects/$project/serviceAccounts/$account" \
  --gcs-source-staging-dir="gs://$bucket/source" \
  --substitutions="_REGISTRY=$registry,_RELEASE=$release"

api_digest="$(gcloud artifacts docker images describe "$registry/api:$release" --project="$project" --format='value(image_summary.digest)')"
web_digest="$(gcloud artifacts docker images describe "$registry/web:$release" --project="$project" --format='value(image_summary.digest)')"
[[ "$api_digest" =~ ^sha256:[a-f0-9]{64}$ && "$web_digest" =~ ^sha256:[a-f0-9]{64}$ ]] || {
  echo 'Registry did not return valid immutable image digests' >&2
  exit 1
}
printf 'api_image = "%s/api@%s"\nweb_image = "%s/web@%s"\n' \
  "$registry" "$api_digest" "$registry" "$web_digest" > "$TF_DIR/release.auto.tfvars"
echo 'Image digests written to release.auto.tfvars. Run and inspect a new Terraform plan before applying.'
