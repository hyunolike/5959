"""Exercise the real Compose parser without starting a Docker daemon."""
import json
import os
import pathlib
import shutil
import subprocess
import unittest

ROOT = pathlib.Path(__file__).parents[1] / "vm"


class ComposeTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("docker"), "Docker Compose CLI is required")
    def test_credentials_survive_real_compose_interpolation(self):
        credential = "example$with'quotes\"and\\trailing\\"
        values = dict.fromkeys([
            "POSTGRES_PASSWORD", "JWT_SECRET", "OGU_BFF_KEY", "OAUTH_ALLOWED_REDIRECT_URIS",
            "KAKAO_CLIENT_ID", "KAKAO_CLIENT_SECRET", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
            "AI_API_KEY", "REDIS_PASSWORD", "OGU_SSE_ALLOWED_ORIGINS",
        ], credential)
        values.update(API_IMAGE="example.invalid/api:test", API_DOMAIN="api.example.invalid",
                      POSTGRES_DB="ogu", POSTGRES_USER="ogu", REDIS_URL="redis://redis:6379")
        result = subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "-f", str(ROOT / "compose.yaml"),
             "config", "--format", "json"],
            env={**os.environ, **values}, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, "Compose configuration failed")
        # `config` escapes each dollar as $$ so its output can itself be a
        # Compose input. Undo that output encoding before checking literal values.
        services = json.loads(result.stdout.replace("$$", "$"))["services"]
        for key in ["AI_API_KEY", "JWT_SECRET", "OGU_BFF_KEY", "GOOGLE_CLIENT_SECRET"]:
            self.assertEqual(services["api"]["environment"][key], credential)
        self.assertEqual(services["postgres"]["environment"]["POSTGRES_PASSWORD"], credential)
        # 009: the AI companion ships switched off and only listed variables reach the container.
        api = services["api"]["environment"]
        self.assertEqual(api["AI_COMPANION_ENABLED"], "false")
        self.assertEqual(api["AI_COMPANION_DAILY_LIMIT"], "45")
        self.assertEqual(api["AI_COMPANION_WAIT"], "10m")
        # 012: several companions may answer one post; the weights and the gap are tunable on the server.
        self.assertEqual(api["AI_COMPANION_COUNT_WEIGHTS"], "60,30,10")
        self.assertEqual(api["AI_COMPANION_FOLLOW_UP_MIN"], "3m")
        self.assertEqual(api["AI_COMPANION_FOLLOW_UP_MAX"], "10m")
        self.assertEqual(services["redis"]["command"][2], credential)
