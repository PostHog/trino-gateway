"""The supported runner cannot select a removed legacy topology."""

import os
from pathlib import Path
import subprocess
import sys
import unittest


class RunnerContract(unittest.TestCase):
    def invoke(self, *args, env=None):
        return subprocess.run([sys.executable, str(Path(__file__).with_name("run_contracts.py")), *args],
                              env=env, capture_output=True, text=True, timeout=15)

    def test_list_uses_only_isolated_pooled_suites(self):
        result = self.invoke("--suite", "pooled", "--list")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("test_pool_lifecycle", result.stdout)
        self.assertIn("test_graceful_shutdown", result.stdout)
        self.assertIn("test_whole_query_cancel", result.stdout)
        self.assertNotIn("test_gateway.", result.stdout)

    def test_removed_topologies_are_not_selectable(self):
        for suite in ("normal", "fault", "real"):
            with self.subTest(suite=suite):
                result = self.invoke("--suite", suite, "--list")
                self.assertEqual(result.returncode, 2)
                self.assertIn("invalid choice", result.stderr)

    def test_execution_requires_explicit_local_fixture_tools(self):
        env = {name: value for name, value in os.environ.items() if not name.startswith("GATEWAY_TEST_")}
        result = self.invoke("--suite", "pooled", env=env)
        self.assertEqual(result.returncode, 2)
        self.assertIn("isolated fixture requires", result.stderr)


if __name__ == "__main__":
    unittest.main()
