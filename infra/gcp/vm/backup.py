#!/usr/bin/env python3
"""Stream pg_dump to a private GCS bucket; never log credentials or dump data."""
import datetime
import http.client
import os
import pathlib
import subprocess
import tempfile
import urllib.parse

from runtime import ROOT, access_token, configuration, check_data_disk, compose_environment


def main():
    os.umask(0o077)
    check_data_disk()
    if not (ROOT / ".env.json").exists():
        raise RuntimeError("API has not been deployed")
    config = configuration()
    filename = datetime.datetime.now(datetime.timezone.utc).strftime("postgres/%Y-%m-%dT%H-%M-%S.dump")
    with tempfile.TemporaryFile() as dump:
        subprocess.run(
            ["docker", "compose", "--project-name", "ogu", "--project-directory", str(ROOT),
             "--env-file", "/dev/null", "-f", str(ROOT / "compose.yaml"),
             "exec", "-T", "postgres", "pg_dump", "-U", "ogu", "-d", "ogu", "-Fc"],
            stdout=dump, stderr=subprocess.DEVNULL, env=compose_environment(ROOT), check=True,
        )
        size = dump.tell()
        if size == 0:
            raise RuntimeError("Empty database dump")
        dump.seek(0)
        query = urllib.parse.urlencode({"uploadType": "media", "name": filename, "ifGenerationMatch": "0"})
        connection = http.client.HTTPSConnection("storage.googleapis.com", timeout=120)
        try:
            connection.putrequest("POST", f"/upload/storage/v1/b/{config['backup_bucket']}/o?{query}")
            connection.putheader("Authorization", "Bearer " + access_token())
            connection.putheader("Content-Type", "application/octet-stream")
            connection.putheader("Content-Length", str(size))
            connection.endheaders()
            while chunk := dump.read(1024 * 1024):
                connection.send(chunk)
            response = connection.getresponse()
            response.read()
            if response.status not in (200, 201):
                raise RuntimeError(f"Backup upload failed (HTTP {response.status})")
        finally:
            connection.close()
    print("Database backup uploaded successfully.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Backup failed ({type(error).__name__}).")
        raise SystemExit(1) from None
