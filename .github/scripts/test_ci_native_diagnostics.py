#!/usr/bin/env python3
"""Exercise CI diagnostic failure paths without crashing a JVM or changing sysctls.

Run: python3 .github/scripts/test_ci_native_diagnostics.py
The real abort/core/GDB path is checked by the prepare command on each CI runner.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


SCRIPT = Path(__file__).with_name("ci-native-diagnostics.sh")


class NativeDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native-diagnostics-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.data = self.root / "diagnostics with spaces"
        for name in ("cores", "jni", "reports"):
            (self.data / name).mkdir(parents=True, exist_ok=True)
        self.pattern = self.root / "core-pattern"
        self.pattern.write_text("test-pattern\n")
        self.output = self.root / "output"
        self.output.touch()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        CI_NATIVE_DIAGNOSTICS_DIR=str(self.data),
                        CI_CORE_PATTERN_FILE=str(self.pattern),
                        GITHUB_OUTPUT=str(self.output),
                        CI_BUILD_DEADLINE=str(int(time.time()) + 30))
        # Homebrew prefixes GNU tools with 'g'; CI uses the ordinary names.
        for name in ("timeout", "readlink", "stat", "sha256sum"):
            executable = shutil.which("g" + name) or shutil.which(name)
            if executable is None:
                self.skipTest("GNU tool is required: " + name)
            (self.bin / name).symlink_to(executable)
        self.stub("java", "exit 0\n")
        self.stub("readelf", "echo 'Build ID: fixture'\n")

    def stub(self, name, body):
        path = self.bin / name
        if path.exists():
            path.unlink()
        path.write_text("#!/usr/bin/env bash\n" + body)
        path.chmod(0o755)

    def invoke(self, *args):
        return subprocess.run(["bash", str(SCRIPT), *args], env=self.env,
                              text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=10)

    def ready(self, count=0):
        (self.data / "ready").touch()
        (self.data / "core-pattern.before").write_text("original-pattern\n")
        for index in range(count):
            (self.data / "cores" / ("core.java.%d" % index)).write_text("fixture")

    def test_command_exit_code_and_arguments_are_preserved(self):
        result = self.invoke("run", "bash", "-c", '[[ "$1" == "a b" ]] && exit 17',
                             "test", "a b")
        self.assertEqual(17, result.returncode, result.stdout)

    def test_expired_budget_does_not_start_command(self):
        self.env["CI_BUILD_DEADLINE"] = str(int(time.time()) - 1)
        marker = self.root / "should-not-exist"
        result = self.invoke("run", "touch", str(marker))
        self.assertEqual(124, result.returncode, result.stdout)
        self.assertFalse(marker.exists())

    def test_running_command_is_stopped_at_deadline(self):
        self.env["CI_BUILD_DEADLINE"] = str(int(time.time()) + 2)
        started = self.root / "started"
        finished = self.root / "finished"
        result = self.invoke("run", "bash", "-c", 'touch "$1"; sleep 20; touch "$2"',
                             "test", str(started), str(finished))
        self.assertEqual(124, result.returncode, result.stdout)
        self.assertTrue(started.exists())
        self.assertFalse(finished.exists())

    def test_failed_prepare_still_restores_host_pattern(self):
        (self.data / "core-pattern.before").write_text("original-pattern\n")
        self.stub("gdb", "exit 99\n")
        result = self.invoke("collect")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual("original-pattern\n", self.pattern.read_text())
        self.assertIn("setup did not complete", result.stdout)

    def test_no_cores_needs_no_gdb_or_artifact(self):
        self.ready()
        self.stub("gdb", "exit 99\n")
        result = self.invoke("collect")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("No native core dumps", result.stdout)
        self.assertEqual("", self.output.read_text())
        self.assertEqual("original-pattern\n", self.pattern.read_text())

    def test_collect_limits_cores_and_libraries_and_survives_gdb_failure(self):
        self.ready(3)
        self.stub("gdb", 'echo "GDB fixture: $*"; exit 7\n')
        for name in ("first.so", "duplicate.so"):
            (self.data / "jni" / name).write_bytes(b"same-library")
        with (self.data / "jni" / "oversized.so").open("wb") as stream:
            stream.truncate(134217729)
        result = self.invoke("collect")
        self.assertEqual(0, result.returncode, result.stdout)
        reports = self.data / "reports"
        self.assertEqual(2, len(list(reports.glob("*.gdb.txt"))))
        self.assertEqual(1, len(list((reports / "libraries").glob("*.so"))))
        self.assertIn("Omitted (128 MiB budget)", (reports / "libraries.txt").read_text())
        self.assertIn("gdb exit status 7", (reports / "collector.txt").read_text())
        self.assertIn("first two cores", (reports / "collector.txt").read_text())
        self.assertEqual("has-cores=true\n", self.output.read_text())
        self.assertEqual("original-pattern\n", self.pattern.read_text())

    def test_collector_timeout_keeps_partial_reports(self):
        self.ready(1)
        self.stub("timeout", '''
[[ "$1 $2 $3" == '--signal=TERM --kill-after=5s 120s' ]] || exit 99
echo partial > "$CI_NATIVE_DIAGNOSTICS_DIR/reports/partial.txt"
exit 124
''')
        result = self.invoke("collect")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("status 124", result.stdout)
        self.assertEqual("partial\n", (self.data / "reports" / "partial.txt").read_text())
        self.assertEqual("has-cores=true\n", self.output.read_text())
        self.assertEqual("original-pattern\n", self.pattern.read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
