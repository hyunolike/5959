# Web deployment automation

> Branch transition: the separately approved [main migration](2026-10-12-web-deploy-main.md) supersedes only the develop references below. This original contract is retained as history; all other release safeguards remain required.

Approved scope: implement a Draft PR after the separately approved manual web release.
No IAM, authentication, networking, database, or API changes are applied by this PR.

## Acceptance criteria

- DEPLOY-AC1: only successful push CI on this repository's develop SHA is eligible.
  A successful ci-ok job and an unchanged current develop ref are required.
- DEPLOY-AC2: serialize production releases without cancelling a release in flight;
  skip obsolete SHAs before build and before traffic promotion.
- DEPLOY-AC3: archive committed web inputs only, build with the existing builder,
  and deploy the digest returned by that exact successful build.
- DEPLOY-AC4: capture explicit old traffic and immutable image identities before any
  mutation. Stage at zero traffic; verify readiness, configuration fingerprints,
  candidate health and public assets before promotion. Verify public health after.
- DEPLOY-AC5: failed/uncertain mutations restore prior traffic when the current
  state is still owned by this release. Never overwrite an unrelated operator's
  changes. Rollback failure is a failed release, with retained recovery metadata.
- DEPLOY-AC6: no PR deploys, credential payloads, IAM setters, secret access, or
  API/DB deployment. Authentication remains disabled pending separate approval.

## Design

GitHub workflow_run listens to completed CI runs on develop. A read-only gate
validates the authoritative run, jobs, and current branch before the privileged
job checks out the eligible SHA. GitHub OIDC uses a separately approved dedicated
identity. An image-only Cloud Run update retains environment, secret references,
runtime identity and network settings. Terraform ignores the image field so later
infrastructure plans cannot silently restore an old release image.

The release script records only identities, traffic, and hashes, not environment
values. A temporary candidate tag is removed after success or recovery. Previous
revisions and images remain available. GitHub concurrency covers this workflow;
manual operators must avoid concurrent service updates during a release.
