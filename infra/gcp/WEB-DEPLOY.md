# ogu-web CI deployment and recovery

Status: **implementation only; disabled until separately approved setup**. This
PR neither creates credentials/IAM bindings nor deploys itself. The workflow must be merged into the default branch before workflow_run can
trigger; the actual GitHub default branch was confirmed as develop. The existing
manual release of 07fd5ab is independent of this automation.

## Release contract

`deploy-web.yml` listens for completed CI on develop. The read-only gate checks
GitHub's run/jobs APIs: same repository, push event, develop, exact SHA, successful
CI and ci-ok, and current develop still at that SHA. No PR or fork can enter the
privileged job. Production releases share `ogu-web-production` concurrency and
are not cancelled. Freshness is checked again before service update and promotion.
A stale queued run fails closed; the newer successful push gets its own run.

The build archives only committed root package inputs and `apps/web` from that
SHA. API, database, local WIP, `.env*`, generated authentication files and Git
metadata are excluded. The existing Cloud Build identity builds only web. Tags
include the full SHA, Actions run ID and attempt; deployment uses the digest from
that exact successful build's results and validates its source SHA.

Before the first service mutation, a recovery artifact records explicit previous
traffic, previous immutable image identities, existing tag mappings and hashes
of runtime/network/IAM configuration. No environment or secret values are saved.
This prepared baseline is uploaded as a separate Actions artifact before any service
mutation. A changed baseline aborts the deployment. The new revision starts with zero traffic and a unique verification tag. Checks
cover Ready status, exact image digest, unchanged configuration and traffic,
health UP, login HTML and icon. The current develop SHA is checked again, then
100% traffic moves to the candidate; public health/assets are checked again.
Only this release's verification tag is removed. Old revisions/images remain.

Terraform ignores only the container image field after initial creation; this
prevents a later infrastructure apply from reverting a released image. Terraform
retains ownership of all runtime/IAM/network configuration. This PR does not
apply Terraform or change those resources.

## Separate setup approval required

Observed on 2026-10-12: no repo secrets/variables/environments and no GCP WIF pools.
Before activation, request execution-time approval for the following specific
resources and bindings in project `ogu-5959-prod` (number `161374563129`). No
existing personal credential or JSON service-account key is copied to GitHub.

| Proposed resource or grant                                                    | Scope and reason                                                                                                                                                                                                                                                     |
| ----------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| WIF pool `ogu-github`, OIDC provider `github-web`                             | GitHub issuer `https://token.actions.githubusercontent.com`; authenticate this workflow without a stored key                                                                                                                                                         |
| New CI service account `ogu-web-deploy@ogu-5959-prod.iam.gserviceaccount.com` | Dedicated build submitter and web deployer, distinct from builder/runtime identities                                                                                                                                                                                 |
| `roles/iam.workloadIdentityUser` on new CI account                            | Only the provider principal set for numeric repo ID `1365536816`; standard access-token impersonation                                                                                                                                                                |
| Custom Cloud Run role                                                         | `run.services.get`, `run.services.update`, `run.services.getIamPolicy`, `run.revisions.get`, `run.revisions.list`; bind **only on ogu-web**                                                                                                                          |
| Custom Cloud Build submit/read role                                           | Project scope: `cloudbuild.builds.create`, `cloudbuild.builds.get`, `cloudbuild.operations.get`, `cloudbuild.locations.get`, `resourcemanager.projects.get`, `serviceusage.services.use`; required to submit and poll the regional build                             |
| `roles/iam.serviceAccountUser`                                                | Only on existing `ogu-build` and `ogu-web` service accounts; required to select builder/runtime identities                                                                                                                                                           |
| Source upload permissions                                                     | `roles/storage.objectCreator` and `roles/storage.objectViewer` only on `ogu-5959-prod-ogu-builds`; committed source upload/read/list, plus custom `storage.buckets.get` on that bucket for gcloud staging metadata lookup; no object delete or bucket administration |
| `roles/artifactregistry.reader`                                               | Only repository `ogu` in `us-central1`; image lookup by Cloud Run deployment                                                                                                                                                                                         |
| GitHub repository variables                                                   | Provider resource name, dedicated CI service-account email and explicit enable switch; set only after approved setup                                                                                                                                                 |

Do not grant Owner/Editor, run.admin, project-wide serviceAccountUser, secret
payload access, database access, IAM setters, VM/network control or API deployment
permissions. The existing builder's repository writer/logging/source reader
permissions stay unchanged. Cloud Run's existing runtime secret access remains.

Use numeric owner/repository IDs and an attribute condition restricting **all**
of the following (confirm exact claims before activation):

```text
assertion.repository_owner_id == '61215550' &&
assertion.repository_id == '1365536816' &&
assertion.ref == 'refs/heads/develop' &&
assertion.event_name == 'workflow_run' &&
assertion.workflow_ref == 'hyunolike/5959/.github/workflows/deploy-web.yml@refs/heads/develop'
```

Map `google.subject=assertion.sub`, repository ID and owner ID attributes. Bind
only the repo principal set on the dedicated CI account. `auth` requests a
one-hour impersonated access token; direct WIF's short token cannot cover a
30-minute build. Standard impersonation uses workloadIdentityUser; do not add
serviceAccountTokenCreator unless a separately justified setup actually needs it.
The token is masked by the action, is passed through `CLOUDSDK_AUTH_ACCESS_TOKEN`,
and is never included in source or release artifacts.

After separate approval and reviewed setup, set:

- `GCP_WEB_WORKLOAD_IDENTITY_PROVIDER`: the approved full provider resource name.
- `GCP_WEB_DEPLOY_SERVICE_ACCOUNT`: the dedicated CI account email.
- `GCP_WEB_DEPLOY_ENABLED`: `true` **last**, after the PR is reviewed and merged.

Consider a protected production environment/required reviewer if the operator
wants approval per release. That would require an explicit workflow and OIDC
condition change; no environment is created here. Initial activation verification
must exercise the real OIDC grants and a harmless read before approving a release.
Least-permission grants are a proposal until that approved integration check.

Official references: [deployment pipeline WIF](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines),
[Cloud Run permissions](https://docs.cloud.google.com/run/docs/reference/iam/roles),
[auth action](https://github.com/google-github-actions/auth),
[custom builder accounts](https://docs.cloud.google.com/build/docs/securing-builds/configure-user-specified-service-accounts),
[conditional service updates](https://docs.cloud.google.com/run/docs/reference/rest/v1/namespaces.services/replaceService).

## Recovery and operational limits

Download `web-release-<run>-<attempt>` and the pre-mutation
`web-release-baseline-<run>-<attempt>` from Actions. Its JSON includes exact prior
revision-to-percent mapping, image digests, candidate revision, SHA, build ID and
phase. `rolled-back` means previous traffic and public health were verified.
`manual-recovery-required` means automatic recovery was not confirmed; never
interpret a failed job as a successful release.

If health or deployment fails, the script restores the recorded explicit prior
traffic rather than the moving `LATEST` target. It does this only when the active
traffic/latest ready revision/tags still belong to the old or candidate release.
If another operator changed the service, it fails closed and retains recovery
metadata. Configuration mismatches fail the release; only traffic is recovered,
so an unexpected configuration difference still requires operator inspection.

Manual recovery after inspecting the current service and recovery artifact:

```bash
gcloud run services describe ogu-web --project=ogu-5959-prod --region=us-central1 \
  --format='json(status.traffic,status.latestReadyRevisionName)'
# Replace with the exact recorded revision(s)/percent(s), after confirming ownership.
gcloud run services update-traffic ogu-web --project=ogu-5959-prod --region=us-central1 \
  --to-revisions=RECORDED_PREVIOUS_REVISION=100 --quiet
curl --fail --silent --show-error --max-time 30 \
  https://ogu-web-161374563129.us-central1.run.app/api/health
```

Do not delete previous revisions/images or change env/secrets/IAM to fix a web
release. Remove only the candidate tag recorded for that attempt after recovery.
SIGINT/SIGTERM enter recovery without being swallowed by health retry handling.
Runner loss, forced termination or expired authentication can prevent rollback;
the prepared artifact provides manual recovery data. GitHub concurrency does not
lock human gcloud/Terraform operations: do not deploy/apply the same service during
an active release. Every image/traffic/tag mutation uses the observed
`metadata.resourceVersion` in a direct Cloud Run v1 conditional PUT. A concurrent
service change is rejected rather than overwritten. Independent IAM changes
are detected with fingerprints; this workflow never writes IAM. Cloud Build timeout bounds the build to 30 minutes;
the workflow's 45-minute limit leaves recovery time within the 1-hour token.

Public verification uses GETs and no login/signup submission. The initial manual
release was also visually checked at desktop and mobile widths; automation checks
health/assets and CI remains responsible for browser regression tests.

## Local validation

```bash
python3 -m unittest discover -s infra/gcp/tests -v
bash infra/tests/deploy_test.sh
shellcheck infra/scripts/*.sh infra/tests/*.sh infra/gcp/scripts/*.sh infra/gcp/vm/*.sh
actionlint -shellcheck= .github/workflows/deploy-web.yml
terraform -chdir=infra/gcp/terraform init -backend=false
terraform -chdir=infra/gcp/terraform fmt -check -recursive
terraform -chdir=infra/gcp/terraform validate
terraform -chdir=infra/gcp/terraform test
```
