# Web deployment: approved main transition

The user approved automatic web deployment from `main` and changing only the existing GitHub OIDC provider's `ref` and `workflow_ref` branch references to `main` on 2026-10-12. Remote `develop` is absent; default `main` contains the tested design and selector fixes in merge `2b658cb`.

This supersedes only the `develop` branch references in [the original deployment contract](2026-10-12-web-deploy-automation.md). Its release isolation, immutable digest, concurrency, staging, health, freshness and ownership-aware recovery requirements remain in force.

- US1-AC1: accept only completed successful same-repository `main` push CI at the exact current SHA, including successful `ci-ok`. Reject PR/fork, develop/feature branch, incorrect workflow, failed/cancelled/incomplete CI, mismatched/stale SHA and missing ci-ok.
- US1-AC2: query current `refs/heads/main` from GitHub; recheck through existing gates before build/service update/promotion. Preserve zero-traffic stage and ownership-aware rollback.
- US1-AC3: change only `assertion.ref` and the branch suffix of `assertion.workflow_ref` on existing `ogu-github/github-web`; preserve numeric owner/repository IDs, workflow_run event, issuer, mappings, allowed audiences, provider state and existing account bindings. No role/account/key/API additions or Release permission changes.
- US1-AC4: validate and publish a separate Draft PR; never merge it automatically. Apply the separately approved OIDC update after local validation/fresh review and before publishing the PR, only after checking no release is running and current main still has the old develop-only workflow. Re-read provider and SA policy immediately before mutation; abort if they differ from the approved baseline. User merge then a new successful main push CI is the automatic release trigger; do not manually dispatch or bypass gates.

Existing repository variables already select the existing provider/account and enable web deployment. Authentication from main is intentionally unavailable until both code and the approved provider condition agree. The initial provider/account setup must not be recreated.
