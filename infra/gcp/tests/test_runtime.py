import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).parents[1] / "vm" / "runtime.py"
spec = importlib.util.spec_from_file_location("runtime", SCRIPT)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeTests(unittest.TestCase):
    def test_disabled_kakao_does_not_require_its_credentials(self):
        values = {key: "x" * 64 for key in runtime.REQUIRED if not key.startswith("KAKAO_")}
        values["KAKAO_LOGIN_ENABLED"] = "false"
        runtime.validate_secrets(values)

    def test_disabled_kakao_still_requires_google_credentials(self):
        values = {key: "x" * 64 for key in runtime.REQUIRED if not key.startswith("KAKAO_")}
        values["KAKAO_LOGIN_ENABLED"] = "false"
        values.pop("GOOGLE_CLIENT_SECRET")
        with self.assertRaisesRegex(ValueError, "GOOGLE_CLIENT_SECRET"):
            runtime.validate_secrets(values)

    def test_ai_companion_is_off_unless_the_deployment_config_turns_it_on(self):
        self.assertEqual(runtime.companion_settings({}), {
            "AI_COMPANION_ENABLED": "false", "AI_COMPANION_DAILY_LIMIT": "45", "AI_COMPANION_WAIT": "10m",
            "AI_COMPANION_POSTING_ENABLED": "false",
        })
        # Only a real boolean true enables it; a truthy string does not.
        self.assertEqual(runtime.companion_settings({"ai_companion_enabled": "true"})["AI_COMPANION_ENABLED"], "false")
        enabled = runtime.companion_settings(
            {"ai_companion_enabled": True, "ai_companion_daily_limit": 5, "ai_companion_wait": "30m"},
        )
        self.assertEqual(enabled, {
            "AI_COMPANION_ENABLED": "true", "AI_COMPANION_DAILY_LIMIT": "5", "AI_COMPANION_WAIT": "30m",
            "AI_COMPANION_POSTING_ENABLED": "false",
        })

    def test_ai_companion_posting_has_its_own_switch(self):
        # 012: turning the reactions on does not turn the posting on, and the reverse.
        only_posting = runtime.companion_settings({"ai_companion_posting_enabled": True})
        self.assertEqual(only_posting["AI_COMPANION_POSTING_ENABLED"], "true")
        self.assertEqual(only_posting["AI_COMPANION_ENABLED"], "false")
        truthy = runtime.companion_settings({"ai_companion_posting_enabled": "true"})
        self.assertEqual(truthy["AI_COMPANION_POSTING_ENABLED"], "false")

    def test_ai_companion_rejects_malformed_limits(self):
        for config in [
            {"ai_companion_daily_limit": -1}, {"ai_companion_daily_limit": "30"},
            {"ai_companion_daily_limit": True}, {"ai_companion_wait": "ten minutes"},
            {"ai_companion_wait": "0m"}, {"ai_companion_wait": 10},
        ]:
            with self.subTest(config=config), self.assertRaises(ValueError):
                runtime.companion_settings(config)

    def test_deployment_config_overrides_companion_fields_in_the_secret(self):
        secret = {key: "x" * 64 for key in runtime.REQUIRED}
        secret["AI_COMPANION_ENABLED"] = "true"
        secret["AI_COMPANION_POSTING_ENABLED"] = "true"
        config = {"project_id": "p", "api_secret": "a", "bff_secret": "b", "web_origin": "https://web.example",
                  "api_image": "image", "api_domain": "api.example"}
        with mock.patch.object(runtime, "secret", side_effect=[json.dumps(secret), "b" * 32]):
            values = runtime.environment(config, "token")
        self.assertEqual(values["AI_COMPANION_ENABLED"], "false")
        self.assertEqual(values["AI_COMPANION_POSTING_ENABLED"], "false")
        self.assertEqual(values["AI_COMPANION_DAILY_LIMIT"], "45")

    def test_wrong_data_disk_is_rejected_before_docker_can_start(self):
        with mock.patch.object(runtime.os.path, "ismount", return_value=True):
            with mock.patch.object(runtime.subprocess, "check_output", side_effect=["expected\n", "other\n"]):
                with self.assertRaisesRegex(RuntimeError, "identity"):
                    runtime.check_data_disk()

    def test_missing_mount_is_rejected_before_docker_can_start(self):
        with mock.patch.object(runtime.os.path, "ismount", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "mounted"):
                runtime.check_data_disk()

    def test_correct_data_disk_is_accepted(self):
        with mock.patch.object(runtime.os.path, "ismount", return_value=True):
            with mock.patch.object(runtime.subprocess, "check_output", side_effect=["expected\n", "expected\n"]):
                runtime.check_data_disk()

    def test_required_credentials_are_checked_without_showing_values(self):
        with self.assertRaisesRegex(ValueError, "AI_API_KEY"):
            runtime.validate_secrets({"JWT_SECRET": "super-secret-value"})

    def test_environment_rejects_multiline_values(self):
        with self.assertRaises(ValueError):
            runtime.serialize_environment({"AI_API_KEY": "first\nsecond"})

    def test_json_preserves_dollar_quotes_and_trailing_backslash(self):
        values = {"PASSWORD": "a$b'c\\d\\"}
        self.assertEqual(
            json.loads(runtime.serialize_environment(values)), values,
        )

    def test_env_key_cannot_inject_another_setting(self):
        with self.assertRaises(ValueError):
            runtime.serialize_environment({"KEY\nOTHER": "value"})

    def test_compose_errors_do_not_disclose_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / ".env.json").write_text('{"PASSWORD": "private"}')
            result = mock.Mock(returncode=1, stderr=b"invalid private", stdout=b"private")
            with mock.patch.object(runtime.subprocess, "run", return_value=result) as run:
                with self.assertRaises(RuntimeError) as raised:
                    runtime.compose(root, ["up", "-d"])
            self.assertNotIn("private", str(raised.exception))
            self.assertTrue(run.call_args.kwargs["capture_output"])
            self.assertEqual(run.call_args.kwargs["env"]["PASSWORD"], "private")

    def test_deploy_refuses_to_rotate_persistent_database_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / ".env.json").write_text('{"POSTGRES_PASSWORD": "existing"}')
            with mock.patch.object(runtime, "compose") as compose:
                with self.assertRaisesRegex(ValueError, "rotation"):
                    runtime.deploy(root, {"POSTGRES_PASSWORD": "changed"})
                compose.assert_not_called()

    def test_failed_pull_preserves_original_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / ".env.json").write_text('{"API_IMAGE":"previous"}\n')
            original = (root / ".env.json").read_bytes()
            with mock.patch.object(runtime, "compose", side_effect=RuntimeError("pull failed")):
                with self.assertRaises(RuntimeError):
                    runtime.deploy(root, {"API_IMAGE": "new"}, lambda: True)
            self.assertEqual((root / ".env.json").read_bytes(), original)

    def test_failed_health_restores_env_and_previous_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / ".env.json").write_text('{"API_IMAGE":"previous"}\n')
            with mock.patch.object(runtime, "compose") as compose:
                with self.assertRaisesRegex(RuntimeError, "health"):
                    runtime.deploy(root, {"API_IMAGE": "new"}, mock.Mock(side_effect=[False, True]))
            self.assertEqual((root / ".env.json").read_text(), '{"API_IMAGE":"previous"}\n')
            self.assertEqual(compose.call_args.args[1], ["up", "-d", "api", "caddy"])

    def test_first_deployment_failure_stops_api_instead_of_claiming_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with mock.patch.object(runtime, "compose") as compose:
                with self.assertRaises(RuntimeError):
                    runtime.deploy(root, {"API_IMAGE": "new"}, lambda: False)
            self.assertFalse((root / ".env.json").exists())
            self.assertEqual(compose.call_args.args[1], ["stop", "api", "caddy"])

    def test_success_promotes_env_with_private_file_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with mock.patch.object(runtime, "compose"):
                runtime.deploy(root, {"API_IMAGE": "new"}, lambda: True)
            self.assertEqual((root / ".env.json").stat().st_mode & 0o777, 0o600)
            self.assertFalse((root / ".env.pending.json").exists())


if __name__ == "__main__":
    unittest.main()
