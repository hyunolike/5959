"""Deployment safety behavior; fake only the external cloud/GitHub boundary."""
import copy
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "web-release.py"
SHA = "a" * 40
DIGEST = "sha256:" + "b" * 64
OLD = "ogu-web-old"
NEW = "ogu-web-git-aaaaaaaa-123-1"


def load():
    if not SCRIPT.exists():
        raise AssertionError("DEPLOY-AC1: release safety implementation is missing")
    spec = importlib.util.spec_from_file_location("web_release", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_fixture():
    return {"id": 99, "name": "CI", "path": ".github/workflows/ci.yml",
            "event": "push", "status": "completed", "conclusion": "success",
            "head_branch": "develop", "head_sha": SHA,
            "repository": {"full_name": "hyunolike/5959"},
            "head_repository": {"full_name": "hyunolike/5959"}}


def service():
    return {"metadata": {"resourceVersion": "1", "generation": 1, "annotations": {"run.googleapis.com/ingress": "all"}},
            "spec": {"template": {"metadata": {"annotations": {}}, "spec": {
                "serviceAccountName": "existing-runtime", "containers": [{
                    "image": "registry/web@sha256:" + "c" * 64,
                    "env": [{"name": "SECRET", "valueFrom": {"secretKeyRef": {"name": "existing", "key": "latest"}}}]
                }]}}},
            "status": {"url": "https://service.invalid", "observedGeneration": 1,
                       "conditions": [{"type": "Ready", "status": "True"}],
                       "latestCreatedRevisionName": OLD, "latestReadyRevisionName": OLD,
                       "traffic": [{"revisionName": OLD, "percent": 100, "latestRevision": True},
                                   {"revisionName": OLD, "tag": "existing", "url": "https://existing.invalid"}]}}


class Cloud:
    def __init__(self, fail=None):
        self.state = service()
        self.fail = fail
        self.health_calls = []
        self.mutations = []
        self.fresh_checks = 0

    def check_version(self, observed, operation):
        if self.fail == "race-" + operation:
            self.state["status"]["traffic"] = [{"revisionName": "operator", "percent": 100}]
            self.state["metadata"]["resourceVersion"] = "operator-version"
        if observed["metadata"]["resourceVersion"] != self.state["metadata"]["resourceVersion"]:
            raise RuntimeError("conditional update conflict")
        self.state["metadata"]["resourceVersion"] += "x"

    def describe(self):
        return copy.deepcopy(self.state)

    def policy(self):
        return {"bindings": [{"role": "roles/run.invoker", "members": ["allUsers"]}]}

    def revision(self, name):
        return {"status": {"imageDigest": "registry/web@" + (DIGEST if name == NEW else "sha256:" + "c" * 64),
                           "conditions": [{"type": "Ready", "status": "True"}]}}

    def stage(self, image, suffix, tag, observed):
        self.check_version(observed, "stage")
        self.mutations.append(("stage", image))
        self.state["spec"]["template"]["spec"]["containers"][0]["image"] = image
        self.state["status"]["latestReadyRevisionName"] = NEW
        self.state["status"]["latestCreatedRevisionName"] = NEW
        self.state["status"]["traffic"].append({"revisionName": NEW, "tag": tag, "url": "https://candidate.invalid"})
        if self.fail == "settings":
            self.state["spec"]["template"]["spec"]["serviceAccountName"] = "changed"
        if self.fail == "stage-uncertain":
            raise RuntimeError("uncertain stage")

    def traffic(self, mapping, observed):
        self.check_version(observed, "promote" if NEW in mapping else "rollback")
        self.mutations.append(("traffic", mapping))
        tags = [dict(t, percent=0) for t in self.state["status"]["traffic"] if t.get("tag")]
        self.state["status"]["traffic"] = [{"revisionName": k, "percent": v} for k, v in mapping.items()] + tags
        if self.fail == "promote-uncertain" and NEW in mapping:
            raise RuntimeError("uncertain promotion")
        if self.fail == "rollback-failed" and OLD in mapping:
            raise RuntimeError("rollback failed")

    def remove_tag(self, tag, observed):
        self.check_version(observed, "cleanup")
        self.mutations.append(("remove", tag))
        self.state["status"]["traffic"] = [t for t in self.state["status"]["traffic"] if t.get("tag") != tag]

    def health(self, url, assets=False):
        self.health_calls.append((url, assets))
        if self.fail in ("candidate", "rollback-failed", "race-rollback") and "candidate" in url:
            raise RuntimeError("candidate health")
        if self.fail == "interrupted" and url == "https://service.invalid":
            self.fail = None
            raise load().ReleaseInterrupted("SIGINT")
        if self.fail == "public" and url == "https://service.invalid":
            raise RuntimeError("public health")
        if self.fail == "concurrent" and "candidate" in url:
            self.state["status"]["traffic"] = [{"revisionName": "operator", "percent": 100}]
            raise RuntimeError("operator changed traffic")

    def fresh(self):
        self.fresh_checks += 1
        if self.fail == "pending-operator" and self.fresh_checks == 2:
            self.state["metadata"]["generation"] = 2
            self.state["metadata"]["resourceVersion"] = "operator-pending"
            self.state["spec"]["traffic"] = [{"revisionName": "operator", "percent": 100}]
        if self.fail == "stale" and self.fresh_checks == 2:
            raise RuntimeError("develop advanced")


class WebReleaseTests(unittest.TestCase):
    def test_DEPLOY_AC1_accepts_exact_successful_push_and_rejects_other_runs(self):
        m = load()
        jobs = [{"name": "ci-ok", "conclusion": "success", "status": "completed"}]
        self.assertEqual(m.eligible(run_fixture(), jobs, SHA, SHA), SHA)
        for key, value in [("event", "pull_request"), ("head_branch", "main"),
                           ("conclusion", "failure"), ("status", "in_progress"),
                           ("head_sha", "d" * 40), ("path", "other.yml")]:
            r = run_fixture(); r[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                m.eligible(r, jobs, SHA, SHA)
        r = run_fixture(); r["head_repository"]["full_name"] = "fork/5959"
        with self.assertRaises(ValueError): m.eligible(r, jobs, SHA, SHA)
        with self.assertRaises(ValueError): m.eligible(run_fixture(), jobs, "d" * 40, SHA)
        with self.assertRaises(ValueError): m.eligible(run_fixture(), [], SHA, SHA)

    def test_DEPLOY_AC4_public_smoke_accepts_copy_changes_and_rejects_missing_or_remote_assets(self):
        m = load()
        html = '<html><input type="email"><input type="password"><link rel="icon" href="/brand-icon.png"></html>'
        self.assertEqual(m.login_icon(html, "https://candidate.invalid"), "https://candidate.invalid/brand-icon.png")
        for bad in [html.replace('type="email"', 'type="text"'),
                    html.replace('rel="icon"', 'rel="stylesheet"'),
                    html.replace('/brand-icon.png', 'https://remote.invalid/icon.png')]:
            with self.assertRaises(ValueError): m.login_icon(bad, "https://candidate.invalid")

    def test_DEPLOY_AC3_build_digest_belongs_to_exact_successful_build(self):
        m = load()
        build = {"status": "SUCCESS", "substitutions": {"_SOURCE_SHA": SHA},
                 "results": {"images": [{"name": "registry/web:release", "digest": DIGEST}]}}
        self.assertEqual(m.build_image(build, SHA, "registry/web:release"), "registry/web@" + DIGEST)
        for mutate in [lambda b: b.update(status="FAILURE"),
                       lambda b: b["substitutions"].update(_SOURCE_SHA="d" * 40),
                       lambda b: b["results"]["images"][0].update(name="registry/api:release"),
                       lambda b: b["results"]["images"][0].update(digest="latest")]:
            bad = copy.deepcopy(build); mutate(bad)
            with self.assertRaises(ValueError): m.build_image(bad, SHA, "registry/web:release")

    def test_DEPLOY_AC3_archive_excludes_api_wip_and_credentials(self):
        m = load()
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); repo = root / "repo"; repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            for name in ["package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", ".dockerignore",
                         "apps/web/src/page.tsx", "apps/api/private", "apps/web/.env.local"]:
                p = repo / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text("committed")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=test", "-c", "user.email=t@example.invalid",
                            "commit", "-qm", "fixture"], check=True)
            sha = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
            (repo / "apps/web/src/page.tsx").write_text("uncommitted")
            (repo / "gha-creds-private.json").write_text("credential")
            m.archive_source(repo, sha, root / "source")
            self.assertEqual((root / "source/apps/web/src/page.tsx").read_text(), "committed")
            self.assertFalse((root / "source/apps/api").exists())
            self.assertFalse((root / "source/apps/web/.env.local").exists())
            self.assertFalse((root / "source/gha-creds-private.json").exists())

    def deploy(self, cloud):
        return load().deploy(cloud, "registry/web@" + DIGEST, "git-aaaaaaaa-123-1", "verify-123-1")

    def test_DEPLOY_AC4_success_stages_checks_promotes_and_preserves_other_tags(self):
        c = Cloud(); result = self.deploy(c)
        self.assertEqual(result["traffic"], {NEW: 100})
        self.assertEqual(c.health_calls, [("https://candidate.invalid", True), ("https://service.invalid", True)])
        self.assertEqual([t["tag"] for t in c.state["status"]["traffic"] if "tag" in t], ["existing"])
        self.assertEqual(c.mutations[1], ("traffic", {NEW: 100}))

    def test_DEPLOY_AC5_prepare_is_read_only_and_rejects_changed_baseline(self):
        m = load(); c = Cloud()
        prepared = m.prepare(c, "registry/web@" + DIGEST, "git-aaaaaaaa-123-1", "verify-123-1")
        self.assertEqual(c.mutations, [])
        self.assertEqual(prepared["previous_traffic"], {OLD: 100})
        self.assertEqual(prepared["previous_images"][OLD], "registry/web@sha256:" + "c" * 64)
        c.state["status"]["traffic"] = [{"revisionName": "operator", "percent": 100}]
        with self.assertRaisesRegex(RuntimeError, "prepared baseline changed"):
            m.deploy(c, "registry/web@" + DIGEST, "git-aaaaaaaa-123-1", "verify-123-1", prepared=prepared)
        self.assertEqual(c.mutations, [])

    def test_DEPLOY_AC5_failures_restore_explicit_previous_revision(self):
        for failure in ["candidate", "public", "stage-uncertain", "promote-uncertain", "stale", "settings"]:
            c = Cloud(failure)
            with self.subTest(failure=failure), self.assertRaises(RuntimeError): self.deploy(c)
            self.assertEqual([(t["revisionName"], t["percent"]) for t in c.state["status"]["traffic"] if t.get("percent")], [(OLD, 100)])
            self.assertEqual([t["tag"] for t in c.state["status"]["traffic"] if "tag" in t], ["existing"])

    def test_DEPLOY_AC5_does_not_overwrite_concurrent_operator_traffic(self):
        c = Cloud("concurrent")
        with self.assertRaisesRegex(RuntimeError, "manual recovery"): self.deploy(c)
        self.assertEqual(c.state["status"]["traffic"], [{"revisionName": "operator", "percent": 100}])

    def test_DEPLOY_AC5_pending_operator_change_is_never_overwritten(self):
        c = Cloud("pending-operator")
        with self.assertRaisesRegex(RuntimeError, "manual recovery"):
            self.deploy(c)
        self.assertEqual(c.state["spec"]["traffic"], [{"revisionName": "operator", "percent": 100}])
        self.assertEqual([change for change in c.mutations if change[0] == "traffic"], [])

    def test_DEPLOY_AC5_every_mutation_is_conditional_on_observed_version(self):
        for operation in ["stage", "promote", "rollback", "cleanup"]:
            c = Cloud("race-" + operation)
            with self.subTest(operation=operation), self.assertRaisesRegex(RuntimeError, "manual recovery"):
                self.deploy(c)
            self.assertEqual(c.state["status"]["traffic"], [{"revisionName": "operator", "percent": 100}])

    def test_DEPLOY_AC5_interruption_enters_recovery(self):
        c = Cloud("interrupted")
        with self.assertRaisesRegex(RuntimeError, "previous traffic restored"):
            self.deploy(c)
        self.assertEqual([(t["revisionName"], t["percent"]) for t in c.state["status"]["traffic"] if t.get("percent")], [(OLD, 100)])

    def test_DEPLOY_AC5_rest_mutation_carries_observed_version_and_only_traffic_change(self):
        m = load(); c = m.GCloud(99, SHA); original = service()
        original["metadata"].update(name="ogu-web", namespace="161374563129")
        with mock.patch.dict(m.os.environ, {"CLOUDSDK_AUTH_ACCESS_TOKEN": "test-only"}):
            with mock.patch.object(m.urllib.request, "urlopen") as request:
                with mock.patch.object(c, "describe", return_value={
                        "metadata": {"generation": "2"}, "status": {"observedGeneration": "2",
                        "conditions": [{"type": "Ready", "status": "True"}]}}):
                    c.traffic({NEW: 100}, original)
        import json
        payload = json.loads(request.call_args.args[0].data)
        self.assertEqual(payload["metadata"]["resourceVersion"], "1")
        self.assertEqual(payload["spec"]["template"], original["spec"]["template"])
        self.assertEqual(payload["spec"]["traffic"], [{"revisionName": NEW, "percent": 100},
                         {"revisionName": OLD, "tag": "existing", "percent": 0}])
        self.assertNotIn("status", payload)
        self.assertEqual(original["metadata"]["resourceVersion"], "1")

    def test_DEPLOY_AC5_reports_rollback_failure(self):
        with self.assertRaisesRegex(RuntimeError, "rollback failed"):
            self.deploy(Cloud("rollback-failed"))


if __name__ == "__main__": unittest.main()
