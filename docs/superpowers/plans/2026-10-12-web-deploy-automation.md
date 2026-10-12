# Web deployment automation implementation plan

Approved execution: native implementation in the independent 5959-design clone.
Original /Users/hyuno/dev/5959 and its Codex session remain untouched.

1. Preserve the previous normal revision/image, deploy exact merged 07fd5ab web
   source using existing authentication, check zero-traffic candidate, then public
   PC/mobile, health and settings. Save evidence outside the repository. COMPLETE.
2. Add failing behavioral tests for CI eligibility, source isolation, immutable
   build selection, stage/promote, failures, rollback, stale commits and concurrent
   operator protection. Implement the release script and web-only build config.
3. Add a disabled-by-default CI completion workflow, image ownership lifecycle,
   minimum-permission setup proposal and recovery/runbook documentation.
4. Run repository infra checks and workflow validation. Obtain a fresh review,
   fix findings and rerun affected checks. Commit, push and create a Draft PR only.
   Observe exact-head CI. Do not merge or activate credentials/IAM.

Activation requires separate execution-time approval of the specific WIF
provider, dedicated service account and narrowly scoped bindings in the runbook.
No user credential or existing service-account key is exported to GitHub.
