import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest


class PrepareSecretsTests(unittest.TestCase):
    def test_google_only_credentials_generate_private_internal_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "scripts").mkdir()
            script = root / "scripts" / "prepare-secrets.py"
            shutil.copy(pathlib.Path(__file__).parents[1] / "scripts" / script.name, script)
            credentials = root / "credentials.json"
            credentials.write_text(json.dumps({
                "AI_API_KEY": "test-private-ai-key", "GOOGLE_CLIENT_ID": "google-id",
                "GOOGLE_CLIENT_SECRET": "test-private-google-secret",
            }))
            result = subprocess.run(["python3", str(script), str(credentials), "--without-kakao"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            values = json.loads((root / "secrets/api-env.json").read_text())
            self.assertEqual(values["KAKAO_LOGIN_ENABLED"], "false")
            for key in ["JWT_SECRET", "POSTGRES_PASSWORD", "REDIS_PASSWORD"]:
                self.assertGreaterEqual(len(values[key]), 32)
            for name in ["api-env.json", "bff-key", "oauth-state"]:
                self.assertEqual((root / "secrets" / name).stat().st_mode & 0o777, 0o600)
            self.assertNotIn("test-private", result.stdout + result.stderr)
            original = (root / "secrets/api-env.json").read_bytes()
            repeated = subprocess.run(["python3", str(script), str(credentials), "--without-kakao"], capture_output=True)
            self.assertNotEqual(repeated.returncode, 0)
            self.assertEqual((root / "secrets/api-env.json").read_bytes(), original)
