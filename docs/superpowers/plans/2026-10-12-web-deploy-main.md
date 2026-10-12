# Web Deploy Main Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore automatic web deployment for current main without weakening existing release guards.

**Architecture:** Keep the existing workflow_run → read-only gate → exact-SHA build/stage/promote pipeline. Replace only develop branch references in workflow and gate; separately change exactly two existing WIF condition references after validation.

**Tech Stack:** GitHub Actions YAML, existing Python stdlib/unittest, gcloud WIF provider update.

**Spec:** `docs/superpowers/specs/2026-10-12-web-deploy-main.md`

## Global Constraints

- No source-repository edits, develop recreation, direct merge, manual deployment, new role/account/key/API, or Release permission changes.
- Preserve ci-ok, exact SHA, stale-source rejection, PR/fork rejection and ownership-aware rollback.
- OIDC changes are limited to the existing provider ref/workflow_ref branch references; retain all other provider fields and service account bindings.

## Review Focus

- Main advances after CI: existing gates reject stale SHA before update/promotion.
- Different event/repository/branch/workflow: gate rejects it before privileged deployment.
- Missing or unsuccessful ci-ok: no eligible release.
- Operator changes service during recovery: rollback must not overwrite them.
- Provider changed concurrently: stop rather than overwriting unapproved condition/mapping fields.

### Task 1: Main branch contract and gate

**Files:** `.github/workflows/deploy-web.yml`, `infra/gcp/scripts/web-release.py`, `infra/gcp/tests/test_web_release.py`, `infra/gcp/WEB-DEPLOY.md`, original contract transition note and this spec/plan.

**Interfaces:** `eligible(run, jobs, current_sha, expected_sha) -> sha`, `gate(run_id, sha) -> sha`; unchanged calling signatures and recovery code.

- [x] Change test fixture head_branch to main; negative branch case to develop. Add gate test whose fake GitHub API accepts only `/git/ref/heads/main`, and rejects stale current main.
- [x] Run `python3 -m unittest discover -s infra/gcp/tests -v`; confirm failure with the old develop gate.
- [x] Replace workflow `branches: [develop]`, `head_branch == 'develop'`, trusted checkout `ref: develop` with main, and gate required head_branch/current GitHub ref/error wording with main. No other release logic changes.
- [x] Run Python safety tests, infra deployment shell tests, shellcheck, actionlint and existing Terraform formatting/validation/tests. Inspect diff against latest main for only expected files.
- [ ] Obtain one fresh whole-branch review and resolve important findings. Perform the approved OIDC switch below while remote main still has its old develop-only workflow, then commit/push/create Draft PR and verify exact HEAD CI.

### Task 2: Approved OIDC switch and handoff

**Resource:** existing `projects/161374563129/locations/global/workloadIdentityPools/ogu-github/providers/github-web` only; no repository file contains credentials.

- [x] Immediately before update, re-read the full provider and SA policy. Compare every captured field with the approved baseline; abort on any difference before constructing the condition. Confirm no web release is running and current main still contains the old develop-only workflow.
- [x] Construct the full existing condition by replacing `refs/heads/develop` with `refs/heads/main` exactly twice; verify numeric IDs and event_name unchanged. Update provider attribute condition only.
- [x] Read provider/SA policy again; assert only the attributeCondition refs changed, with issuer, mappings, audiences/state/name and bindings identical. Check old production revision/traffic remain unchanged.
- [ ] Report green Draft PR and completed approved OIDC change for user merge. After user merge, track new main CI and automatic deployment; verify exact deployed SHA, traffic, health and public desktop/mobile. No duplicate/manual deployment.

The provider API does not expose an atomic condition compare-and-set. A fresh before-write comparison and exact after-write comparison detect observed drift; they do not eliminate a concurrent same-field write in the read/write interval. Keep the interval short and do not automatically reset another operator’s changes.
