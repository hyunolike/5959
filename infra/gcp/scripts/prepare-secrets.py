#!/usr/bin/env python3
"""Create root-private Secret Manager payload files from operator credentials."""
import argparse
import json
import os
import pathlib
import secrets


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("credentials", type=pathlib.Path, help="JSON with real AI and OAuth credentials")
    parser.add_argument("--without-kakao", action="store_true", help="Enable only Google OAuth")
    args = parser.parse_args()
    values = json.loads(args.credentials.read_text())
    required = ["AI_API_KEY", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET"]
    if not args.without_kakao:
        required += ["KAKAO_CLIENT_ID", "KAKAO_CLIENT_SECRET"]
    for key in required:
        if not isinstance(values.get(key), str) or not values[key].strip():
            parser.error(f"Required credential missing: {key}")
    values["KAKAO_LOGIN_ENABLED"] = "false" if args.without_kakao else "true"
    for key in ["POSTGRES_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD"]:
        values.setdefault(key, secrets.token_hex(32))
    os.umask(0o077)
    destination = pathlib.Path(__file__).resolve().parents[1] / "secrets"
    destination.mkdir(mode=0o700, exist_ok=True)
    payloads = {
        "api-env.json": json.dumps(values, ensure_ascii=False),
        "bff-key": secrets.token_hex(32),
        "oauth-state": secrets.token_hex(32),
    }
    if any((destination / name).exists() for name in payloads):
        parser.error("Secret files already exist; refusing to rotate database/authentication credentials")
    for name, content in payloads.items():
        descriptor = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            output.write(content)
    print("Private secret files prepared. Values are not printed.")


if __name__ == "__main__":
    main()
