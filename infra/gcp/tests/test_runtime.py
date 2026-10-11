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
