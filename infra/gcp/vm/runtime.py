#!/usr/bin/env python3
"""VM runtime. Secrets stay in memory and root-only files, never metadata/logs."""
import base64
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.request
import urllib.parse

ROOT = pathlib.Path("/opt/ogu")
METADATA = "http://metadata.google.internal/computeMetadata/v1/"
REQUIRED = [
    "AI_API_KEY", "POSTGRES_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD",
    "KAKAO_CLIENT_ID", "KAKAO_CLIENT_SECRET", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
]


def metadata(path):
    request = urllib.request.Request(METADATA + path, headers={"Metadata-Flavor": "Google"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read().decode()


def access_token():
    return json.loads(metadata("instance/service-accounts/default/token"))["access_token"]


def secret(project, name, token):
    url = f"https://secretmanager.googleapis.com/v1/projects/{project}/secrets/{name}/versions/latest:access"
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)["payload"]["data"]
    return base64.b64decode(payload).decode().strip()


def validate_secrets(values):
    kakao_enabled = values.get("KAKAO_LOGIN_ENABLED", "true")
    if kakao_enabled not in ["true", "false"]:
        raise ValueError("KAKAO_LOGIN_ENABLED must be true or false")
    for key in REQUIRED:
        if key.startswith("KAKAO_") and kakao_enabled == "false":
            continue
        if not isinstance(values.get(key), str) or not values[key].strip():
            raise ValueError(f"Required secret field missing: {key}")
    for key in ["JWT_SECRET", "REDIS_PASSWORD"]:
        if len(values[key]) < 32:
            raise ValueError(f"Secret field too short: {key}")


def serialize_environment(values):
    for key, value in values.items():
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError("Invalid environment key")
        if not isinstance(value, str):
            raise ValueError(f"Environment value must be a string: {key}")
        if any(char in value for char in ["\n", "\r", "\x00"]):
            raise ValueError(f"Multiline environment value rejected: {key}")
    return json.dumps(values, ensure_ascii=False) + "\n"


def check_data_disk():
    if not os.path.ismount("/var/lib/ogu"):
        raise RuntimeError("Data disk is not mounted")
    expected = subprocess.check_output(
        ["blkid", "-s", "UUID", "-o", "value", "/dev/disk/by-id/google-ogu-data"], text=True,
    ).strip()
    actual = subprocess.check_output(
        ["findmnt", "-n", "-o", "UUID", "--mountpoint", "/var/lib/ogu"], text=True,
    ).strip()
    if not expected or expected != actual:
        raise RuntimeError("Data disk identity does not match")


def private_write(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data if isinstance(data, bytes) else data.encode())


def compose_environment(root, env_file=None):
    values = json.loads((env_file or root / ".env.json").read_text())
    serialize_environment(values)
    return {**os.environ, **values}


def compose(root, args, env_file=None):
    command = ["docker", "compose", "--project-name", "ogu", "--project-directory", str(root),
               "--env-file", "/dev/null", "-f", str(root / "compose.yaml"), *args]
    # Supply literal process values, avoiding dotenv quote/interpolation rules.
    # Compose errors can echo credentials, so never inherit its output.
    result = subprocess.run(command, env=compose_environment(root, env_file), capture_output=True)
    if result.returncode:
        raise RuntimeError("Container operation failed; inspect container health")


def healthy():
    for _ in range(90):
        try:
            with urllib.request.urlopen("http://127.0.0.1:8080/actuator/health", timeout=3) as response:
                if json.load(response).get("status") == "UP":
                    return True
        except (OSError, ValueError):
            pass
        time.sleep(3)
    return False


def deploy(root, values, health_check=healthy):
    current, pending = root / ".env.json", root / ".env.pending.json"
    previous = current.read_bytes() if current.exists() else None
    if previous is not None:
        old_values = json.loads(previous)
        for key in ["POSTGRES_PASSWORD", "REDIS_PASSWORD"]:
            if old_values.get(key) != values.get(key):
                raise ValueError("Database credentials require a separate rotation procedure")
    private_write(pending, serialize_environment(values))
    promoted = False
    try:
        # A failed download cannot alter the active environment.
        compose(root, ["pull", "api", "caddy"], pending)
        os.replace(pending, current)
        promoted = True
        compose(root, ["up", "-d", "postgres", "redis", "api", "caddy"])
        if not health_check():
            raise RuntimeError("API health check failed")
    except Exception:
        if promoted and previous is not None:
            private_write(pending, previous)
            os.replace(pending, current)
            compose(root, ["up", "-d", "api", "caddy"])
            if not health_check():
                raise RuntimeError("Deployment and rollback health checks failed") from None
        elif promoted:
            # Keep Postgres/data alive. There is no previous image on first deployment.
            compose(root, ["stop", "api", "caddy"])
            current.unlink(missing_ok=True)
        pending.unlink(missing_ok=True)
        raise


def configuration():
    return json.loads(metadata("instance/attributes/deployment-config"))


def companion_settings(config):
    """AI companion switches (009, 012). Each is off unless the deployment config turns it on."""
    limit = config.get("ai_companion_daily_limit", 45)
    if isinstance(limit, bool) or not isinstance(limit, int) or limit < 0:
        raise ValueError("ai_companion_daily_limit must be a non-negative integer")
    wait = config.get("ai_companion_wait", "10m")
    if not isinstance(wait, str) or not re.fullmatch(r"[1-9][0-9]*[smh]", wait):
        raise ValueError("ai_companion_wait must look like 10m, 30s or 1h")
    return {
        "AI_COMPANION_ENABLED": "true" if config.get("ai_companion_enabled", False) is True else "false",
        # 012: writing posts is a separate, riskier switch. A truthy string does not turn it on either.
        "AI_COMPANION_POSTING_ENABLED":
            "true" if config.get("ai_companion_posting_enabled", False) is True else "false",
        "AI_COMPANION_DAILY_LIMIT": str(limit),
        "AI_COMPANION_WAIT": wait,
    }


def environment(config, token):
    values = json.loads(secret(config["project_id"], config["api_secret"], token))
    values["KAKAO_LOGIN_ENABLED"] = "true" if config.get("kakao_login_enabled", True) else "false"
    # The deployment config decides; a stray field in the secret must not switch the feature on.
    values.update(companion_settings(config))
    validate_secrets(values)
    bff = secret(config["project_id"], config["bff_secret"], token)
    if len(bff) < 32:
        raise ValueError("BFF key must have at least 32 characters")
    origin = config["web_origin"]
    return {
        **values,
        "API_IMAGE": config["api_image"],
        "API_DOMAIN": config["api_domain"],
        "POSTGRES_DB": "ogu", "POSTGRES_USER": "ogu",
        "OGU_BFF_KEY": bff,
        "REDIS_URL": "redis://:" + urllib.parse.quote(values["REDIS_PASSWORD"], safe="") + "@redis:6379",
        "OGU_SSE_ALLOWED_ORIGINS": origin,
        "OAUTH_ALLOWED_REDIRECT_URIS": ",".join(
            f"{origin}/api/auth/oauth/{provider}/callback"
            for provider in (["kakao", "google"] if values["KAKAO_LOGIN_ENABLED"] == "true" else ["google"])
        ),
    }


def main():
    os.umask(0o077)
    check_data_disk()
    config = configuration()
    if not config["api_image"]:
        print("Infrastructure prepared; API image has not been configured.")
        return
    token = access_token()
    values = environment(config, token)
    docker_config = pathlib.Path("/run/ogu/docker")
    docker_config.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.environ["DOCKER_CONFIG"] = str(docker_config)
    try:
        subprocess.run(
            ["docker", "login", "-u", "oauth2accesstoken", "--password-stdin", config["registry"]],
            input=token.encode(), check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        deploy(ROOT, values)
    finally:
        (docker_config / "config.json").unlink(missing_ok=True)
    print("API deployment passed the local health check.")


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["--check-data-disk"]:
            check_data_disk()
        else:
            main()
    except Exception as error:
        # Do not print HTTP bodies, secret payloads or command environments.
        print(f"Deployment failed ({type(error).__name__}); inspect container health.")
        raise SystemExit(1) from None
