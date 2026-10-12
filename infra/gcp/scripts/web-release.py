#!/usr/bin/env python3
"""CI-gated, web-only Cloud Run release. Never prints configuration values."""
import argparse
import copy
import hashlib
from html.parser import HTMLParser
import io
import json
import os
import pathlib
import re
import signal
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse

REPO = "hyunolike/5959"
PROJECT = "ogu-5959-prod"
REGION = "us-central1"
SERVICE = "ogu-web"
REGISTRY = f"{REGION}-docker.pkg.dev/{PROJECT}/ogu"
BUILDER = f"ogu-build@{PROJECT}.iam.gserviceaccount.com"
BUCKET = f"{PROJECT}-ogu-builds"


def command(args, binary=False):
    result = subprocess.run(args, capture_output=True, text=not binary)
    if result.returncode:
        # Cloud responses may contain environment values. Do not echo stderr.
        raise RuntimeError(f"{args[0]} {args[1]} failed (exit {result.returncode}); inspect the provider console")
    return result.stdout


def eligible(run, jobs, current_sha, expected_sha):
    if not re.fullmatch(r"[a-f0-9]{40}", expected_sha):
        raise ValueError("invalid source SHA")
    required = {"name": "CI", "path": ".github/workflows/ci.yml", "event": "push",
                "status": "completed", "conclusion": "success", "head_branch": "develop",
                "head_sha": expected_sha}
    if any(run.get(k) != v for k, v in required.items()):
        raise ValueError("CI run is not an eligible successful develop push")
    if any(run.get(k, {}).get("full_name") != REPO for k in ("repository", "head_repository")):
        raise ValueError("foreign repository cannot deploy")
    if current_sha != expected_sha:
        raise ValueError("develop advanced; skip obsolete SHA")
    if not any(j.get("name") == "ci-ok" and j.get("conclusion") == "success"
               and j.get("status") == "completed" for j in jobs):
        raise ValueError("successful ci-ok job is required")
    return expected_sha


def gate(run_id, sha):
    root = f"repos/{REPO}"
    run = json.loads(command(["gh", "api", f"{root}/actions/runs/{run_id}"]))
    pages = json.loads(command(["gh", "api", "--paginate", "--slurp",
                               f"{root}/actions/runs/{run_id}/jobs?filter=latest&per_page=100"]))
    current = json.loads(command(["gh", "api", f"{root}/git/ref/heads/develop"]))["object"]["sha"]
    return eligible(run, [j for p in pages for j in p["jobs"]], current, sha)


def archive_source(repo, sha, destination):
    archive = command(["git", "-C", str(repo), "archive", sha, "package.json", "pnpm-lock.yaml",
                       "pnpm-workspace.yaml", ".dockerignore", "apps/web"], binary=True)
    destination.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for member in source.getmembers():
            path = pathlib.PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or member.issym() or member.islnk():
                raise ValueError("unsafe source archive entry")
            if any(part.startswith(".env") or part.startswith("gha-creds-") for part in path.parts):
                continue
            target = destination / member.name
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as contents, target.open("wb") as output:
                    shutil.copyfileobj(contents, output)
                target.chmod(member.mode & 0o777)
            else:
                raise ValueError("unsafe source archive entry")


def build_image(build, sha, tag):
    if build.get("status") != "SUCCESS" or build.get("substitutions", {}).get("_SOURCE_SHA") != sha:
        raise ValueError("build failed or belongs to a different SHA")
    images = [i for i in build.get("results", {}).get("images", []) if i.get("name") == tag]
    if len(images) != 1 or not re.fullmatch(r"sha256:[a-f0-9]{64}", images[0].get("digest", "")):
        raise ValueError("exact build did not return an immutable web digest")
    return tag.rsplit(":", 1)[0] + "@" + images[0]["digest"]


def fingerprint(service, policy):
    runtime = copy.deepcopy(service["spec"]["template"]["spec"])
    for container in runtime["containers"]:
        container.pop("image", None)
    network = {where: {k: v for k, v in obj.get("metadata", {}).get("annotations", {}).items()
                       if any(word in k for word in ("ingress", "vpc", "network", "cloudsql", "encryption"))}
               for where, obj in (("service", service), ("template", service["spec"]["template"]))}
    values = {"runtime": runtime, "network": network,
              "iam": {k: policy[k] for k in ("bindings", "version") if k in policy}}
    return {k: hashlib.sha256(json.dumps(v, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
            for k, v in values.items()}


def traffic_map(service):
    mapping = {}
    for target in service["status"]["traffic"]:
        if target.get("percent", 0):
            name = target.get("revisionName")
            if not name:
                raise RuntimeError("traffic has no explicit revision; manual recovery required")
            mapping[name] = mapping.get(name, 0) + target["percent"]
    if sum(mapping.values()) != 100:
        raise RuntimeError("traffic is not a complete explicit map")
    return mapping


def tags(service):
    return {t["tag"]: t["revisionName"] for t in service["status"]["traffic"] if t.get("tag")}


def settled(service, require_ready=True):
    metadata, status = service["metadata"], service["status"]
    if status.get("observedGeneration") != metadata.get("generation"):
        raise RuntimeError("service has an unreconciled change; manual recovery required")
    ready = next((c.get("status") for c in status.get("conditions", []) if c.get("type") == "Ready"), None)
    if ready not in (("True",) if require_ready else ("True", "False")):
        raise RuntimeError("service reconciliation is incomplete; manual recovery required")
    if require_ready and status.get("latestCreatedRevisionName") != status.get("latestReadyRevisionName"):
        raise RuntimeError("latest created revision is not ready; manual recovery required")


class LoginHTML(HTMLParser):
    def __init__(self):
        super().__init__()
        self.inputs, self.icons, self.html = set(), [], False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.html |= tag == "html"
        if tag == "input":
            self.inputs.add(attrs.get("type"))
        if tag == "link" and "icon" in attrs.get("rel", "").split():
            self.icons.append(attrs.get("href", ""))


def login_icon(html, origin):
    page = LoginHTML(); page.feed(html)
    if not page.html or not {"email", "password"}.issubset(page.inputs) or not page.icons:
        raise ValueError("public login form or icon missing")
    url = urllib.parse.urljoin(origin + "/", page.icons[0])
    if urllib.parse.urlsplit(url).netloc != urllib.parse.urlsplit(origin).netloc:
        raise ValueError("public icon must use the service origin")
    return url


def prepare(cloud, image, suffix, tag):
    before = cloud.describe()
    settled(before)
    previous = traffic_map(before)
    previous_tags = tags(before)
    if tag in previous_tags:
        raise RuntimeError("candidate tag already exists")
    signature = fingerprint(before, cloud.policy())
    revision = f"{SERVICE}-{suffix}"
    images = {name: cloud.revision(name)["status"]["imageDigest"] for name in previous}
    return {"previous_traffic": previous, "previous_images": images, "previous_tags": previous_tags,
            "previous_latest_revision": before["status"].get("latestReadyRevisionName"),
            "service_url": before["status"]["url"], "configuration_hashes": signature,
            "revision": revision, "image": image, "phase": "prepared"}


class ReleaseInterrupted(Exception):
    """Signals must bypass retry catches and enter deployment recovery."""


def deploy(cloud, image, suffix, tag, record=lambda data: None, prepared=None):
    cloud.fresh()
    journal = prepare(cloud, image, suffix, tag)
    if prepared is not None and any(prepared.get(k) != v for k, v in journal.items()):
        raise RuntimeError("prepared baseline changed; no service mutation performed")
    previous, previous_tags = journal["previous_traffic"], journal["previous_tags"]
    signature, revision = journal["configuration_hashes"], journal["revision"]
    before = cloud.describe()
    settled(before)
    if (traffic_map(before) != previous or tags(before) != previous_tags
            or fingerprint(before, cloud.policy()) != signature
            or before["status"].get("latestReadyRevisionName") != journal["previous_latest_revision"]):
        raise RuntimeError("prepared baseline changed; no service mutation performed")
    record(journal)
    try:
        # Even a failed command may have mutated Cloud Run, so recovery starts here.
        cloud.stage(image, suffix, tag, before)
        candidate = cloud.describe()
        settled(candidate)
        ready = cloud.revision(revision)["status"]
        if not any(c.get("type") == "Ready" and c.get("status") == "True" for c in ready.get("conditions", [])):
            raise RuntimeError("candidate revision is not ready")
        if ready.get("imageDigest") != image:
            raise RuntimeError("candidate digest differs from exact build")
        if fingerprint(candidate, cloud.policy()) != signature:
            raise RuntimeError("runtime, network or IAM configuration changed")
        if traffic_map(candidate) != previous or tags(candidate) != {**previous_tags, tag: revision}:
            raise RuntimeError("traffic or tags changed before verification")
        target = next(t for t in candidate["status"]["traffic"] if t.get("tag") == tag and t.get("revisionName") == revision)
        cloud.health(target["url"], assets=True)
        cloud.fresh()
        current = cloud.describe()
        settled(current)
        if (traffic_map(current) != previous or tags(current) != {**previous_tags, tag: revision}
                or current["status"].get("latestReadyRevisionName") != revision
                or fingerprint(current, cloud.policy()) != signature):
            raise RuntimeError("service changed during verification")
        journal["phase"] = "promoting"; record(journal)
        cloud.traffic({revision: 100}, current)
        current = cloud.describe()
        settled(current)
        if traffic_map(current) != {revision: 100} or fingerprint(current, cloud.policy()) != signature:
            raise RuntimeError("promotion did not preserve configuration and traffic")
        cloud.health(before["status"]["url"], assets=True)
        cloud.remove_tag(tag, current)
        current = cloud.describe()
        settled(current)
        if traffic_map(current) != {revision: 100} or tags(current) != previous_tags:
            raise RuntimeError("unexpected state after candidate tag cleanup")
        journal.update(phase="success", traffic={revision: 100}); record(journal)
        return journal
    except Exception as failure:
        journal["phase"] = "failed"; record(journal)
        try:
            current = cloud.describe()
            settled(current, require_ready=False)
            latest = current["status"].get("latestReadyRevisionName")
            allowed = (before["status"].get("latestReadyRevisionName"), revision)
            active = traffic_map(current)
            current_tags = tags(current)
            own_tag = current_tags.pop(tag, None)
            if (latest not in allowed or current["status"].get("latestCreatedRevisionName") not in allowed
                    or active not in (previous, {revision: 100})
                    or current_tags != previous_tags or own_tag not in (None, revision)):
                raise RuntimeError("service changed outside this release; manual recovery required")
            cloud.traffic(previous, current)
            restored = cloud.describe()
            settled(restored, require_ready=False)
            if traffic_map(restored) != previous:
                raise RuntimeError("rollback traffic verification failed")
            if own_tag:
                cloud.remove_tag(tag, restored)
            cloud.health(before["status"]["url"])
            journal["phase"] = "rolled-back"; record(journal)
        except Exception as recovery:
            journal["phase"] = "manual-recovery-required"; record(journal)
            raise RuntimeError(f"release failed; rollback failed; manual recovery required: {recovery}") from failure
        raise RuntimeError(f"release failed; previous traffic restored: {failure}") from failure


class GCloud:
    def __init__(self, run_id, sha):
        self.run_id, self.sha = run_id, sha

    def call(self, *args):
        return command(["gcloud", *args, f"--project={PROJECT}", f"--region={REGION}", "--quiet", "--format=json"])

    def describe(self):
        return json.loads(self.call("run", "services", "describe", SERVICE))

    def policy(self):
        return json.loads(self.call("run", "services", "get-iam-policy", SERVICE))

    def revision(self, name):
        return json.loads(self.call("run", "revisions", "describe", name))

    def replace(self, observed, change):
        # gcloud update-traffic/replace refetch and overwrite resourceVersion.
        # Direct v1 PUT retains the version that our ownership checks observed.
        version = observed.get("metadata", {}).get("resourceVersion")
        if not version:
            raise RuntimeError("missing resourceVersion; conditional update refused")
        desired = copy.deepcopy(observed)
        desired.pop("status", None)
        metadata = desired["metadata"]
        for key in ("creationTimestamp", "generation", "selfLink", "uid"):
            metadata.pop(key, None)
        for key in ("serving.knative.dev/creator", "serving.knative.dev/lastModifier",
                    "run.googleapis.com/creatorEmail", "run.googleapis.com/lastModifierEmail",
                    "run.googleapis.com/operation-id"):
            metadata.get("annotations", {}).pop(key, None)
        change(desired)
        desired["metadata"]["resourceVersion"] = version
        token = os.environ.get("CLOUDSDK_AUTH_ACCESS_TOKEN")
        if not token:
            raise RuntimeError("approved short-lived access token is required")
        url = f"https://{REGION}-run.googleapis.com/apis/serving.knative.dev/v1/namespaces/{PROJECT}/services/{SERVICE}"
        request = urllib.request.Request(url, data=json.dumps(desired).encode(), method="PUT",
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response.read()  # Never print/store raw configuration responses.
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"conditional Cloud Run update failed (HTTP {error.code})") from None
        except (urllib.error.URLError, TimeoutError):
            raise RuntimeError("conditional Cloud Run update outcome uncertain") from None
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            current = self.describe()
            status = current["status"]
            if (int(status.get("observedGeneration", 0)) == int(current["metadata"].get("generation", 1))
                    and any(c.get("type") == "Ready" and c.get("status") in ("True", "False") for c in status.get("conditions", []))):
                return
            time.sleep(3)
        raise RuntimeError("conditional update readiness timed out")

    @staticmethod
    def explicit_traffic(observed):
        # Pin LATEST to the already resolved revision; preserve all existing tags.
        return [{k: t[k] for k in ("revisionName", "percent", "tag") if k in t}
                for t in observed["status"]["traffic"]]

    def stage(self, image, suffix, tag, observed):
        def change(desired):
            template = desired["spec"]["template"]
            template.setdefault("metadata", {})["name"] = f"{SERVICE}-{suffix}"
            template["spec"]["containers"][0]["image"] = image
            desired["spec"]["traffic"] = self.explicit_traffic(observed) + [
                {"revisionName": f"{SERVICE}-{suffix}", "percent": 0, "tag": tag}]
        self.replace(observed, change)

    def traffic(self, mapping, observed):
        def change(desired):
            existing_tags = [{"revisionName": t["revisionName"], "tag": t["tag"], "percent": 0}
                             for t in observed["status"]["traffic"] if t.get("tag")]
            desired["spec"]["traffic"] = [{"revisionName": k, "percent": v} for k, v in mapping.items()] + existing_tags
        self.replace(observed, change)

    def remove_tag(self, tag, observed):
        def change(desired):
            targets = self.explicit_traffic(observed)
            for target in targets:
                if target.get("tag") == tag:
                    target.pop("tag")
            desired["spec"]["traffic"] = [t for t in targets if t.get("percent", 0) or t.get("tag")]
        self.replace(observed, change)

    def fresh(self):
        gate(self.run_id, self.sha)

    def health(self, url, assets=False):
        for attempt in range(6):
            try:
                data = json.loads(command(["curl", "--fail", "--silent", "--show-error", "--max-time", "20", url + "/api/health"]))
                if data.get("status") != "UP":
                    raise RuntimeError("web health is not UP")
                if assets:
                    # Public GETs only. Follow / -> /login; never submit auth forms.
                    html = command(["curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "20", url + "/"])
                    icon_url = login_icon(html, url)
                    icon = command(["curl", "--fail", "--silent", "--show-error", "--max-time", "20",
                                    "--output", os.devnull, "--write-out", "%{http_code} %{content_type}", icon_url])
                    if not icon.startswith("200 image/"):
                        raise RuntimeError("public icon is missing")
                return
            except (RuntimeError, ValueError):
                if attempt == 5:
                    raise RuntimeError("public health/assets verification failed") from None
                time.sleep(5)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ci-run-id", required=True, type=int)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--release-id")
    parser.add_argument("--gate-only", action="store_true")
    parser.add_argument("--deploy-prepared", action="store_true")
    parser.add_argument("--output", default="web-release-result.json")
    args = parser.parse_args()
    sha = gate(args.ci_run_id, args.sha)
    if args.gate_only:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"sha={sha}\n")
        return
    if not re.fullmatch(r"[0-9]+-[0-9]+", args.release_id or ""):
        raise ValueError("release-id must be GitHub run id and attempt")
    repo = pathlib.Path(__file__).resolve().parents[3]
    if command(["git", "-C", str(repo), "rev-parse", "HEAD"]).strip() != sha:
        raise ValueError("checkout is not the CI SHA")
    cloud = GCloud(args.ci_run_id, sha)
    release = f"{sha}-{args.release_id}"
    tag = f"{REGISTRY}/web:{release}"
    output_path = pathlib.Path(args.output)

    def record(data):
        output_path.write_text(json.dumps({"sha": sha, "ci_run_id": args.ci_run_id, **data}, indent=2) + "\n")

    suffix, verify_tag = f"git-{sha[:8]}-{args.release_id}", f"verify-{args.release_id}"
    if args.deploy_prepared:
        prepared = json.loads(output_path.read_text())
        if prepared.get("sha") != sha or prepared.get("ci_run_id") != args.ci_run_id or prepared.get("phase") != "prepared":
            raise ValueError("prepared artifact belongs to a different release")
        build_id = prepared["build_id"]
        build = json.loads(cloud.call("builds", "describe", build_id))
        image = build_image(build, sha, tag)
        if image != prepared["image"] or prepared["revision"] != f"{SERVICE}-{suffix}":
            raise ValueError("prepared image/revision belongs to a different attempt")
        def deployment_record(data):
            record({"build_id": build_id, **data})
        deploy(cloud, image, suffix, verify_tag, deployment_record, prepared=prepared)
        print(f"Released {sha} to {SERVICE}; immutable image {image}")
        return
    with tempfile.TemporaryDirectory(prefix="ogu-web-source-") as directory:
        source = pathlib.Path(directory) / "source"
        archive_source(repo, sha, source)
        build = json.loads(cloud.call("builds", "submit", str(source), "--async",
            f"--config={repo / 'infra/gcp/cloudbuild.web.yaml'}",
            f"--service-account=projects/{PROJECT}/serviceAccounts/{BUILDER}",
            f"--gcs-source-staging-dir=gs://{BUCKET}/source",
            f"--substitutions=_REGISTRY={REGISTRY},_RELEASE={release},_SOURCE_SHA={sha}"))
        build_id = build["id"]
        record({"phase": "building", "build_id": build_id})
        deadline = time.monotonic() + 1800
        while build.get("status") in ("QUEUED", "PENDING", "WORKING"):
            if time.monotonic() >= deadline:
                raise RuntimeError("build wait timed out; no service mutation performed")
            time.sleep(15)
            build = json.loads(cloud.call("builds", "describe", build_id))
        image = build_image(build, sha, tag)
        cloud.fresh()
        record({"build_id": build_id, **prepare(cloud, image, suffix, verify_tag)})
    print(f"Prepared {sha}; upload recovery artifact before --deploy-prepared")


if __name__ == "__main__":
    def terminate(signum, frame):
        raise ReleaseInterrupted("runner interruption requested")
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    try:
        main()
    except (RuntimeError, ValueError, ReleaseInterrupted) as error:
        print(str(error), file=__import__("sys").stderr)
        raise SystemExit(1)
