"""Orchestration-only fixture: a same-pin pristine BE must not be baked as patched."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


SOURCE = Path(__file__).resolve().parent
REPO = SOURCE.parents[1]


class BakeImagesTests(unittest.TestCase):
    def test_missing_handler_rejects_same_pin_pristine_be_before_docker_build(self):
        baseline = (REPO / "doris-patches" / "BASELINE").read_text()
        pin = next(line.split("=", 1)[1] for line in baseline.splitlines() if line.startswith("PIN_SHA="))
        with tempfile.TemporaryDirectory(prefix="duckbridge-bake-guard-") as temporary:
            root = Path(temporary)
            output = root / "output"
            spi = output / "be/lib/jni/spi"
            jdbc = output / "be/plugins/jni/jdbc"
            spi.mkdir(parents=True)
            jdbc.mkdir(parents=True)
            # These are explicitly fake orchestration inputs, never runtime/semantic evidence.
            (output / "be/lib/doris_be").write_text(f"__asan_init {pin}\n")
            with zipfile.ZipFile(spi / "doris-jni-spi.jar", "w") as archive:
                archive.writestr("META-INF/doris/jni-plugin-api-version.properties", "api.version=6.0\n")
            with zipfile.ZipFile(jdbc / "jdbc-scanner.jar", "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            commands = root / "commands"
            commands.mkdir()
            git = commands / "git"
            git.write_text(f"#!/bin/sh\nprintf '%s\\n' '{pin}'\n")
            git.chmod(0o755)
            docker = commands / "docker"
            docker.write_text("#!/bin/sh\n[ \"$1\" = ps ] && exit 0\necho \"$*\" >> \"$BAKE_CALLS\"\nexit 0\n")
            docker.chmod(0o755)
            calls = root / "docker-build-calls"
            env = dict(os.environ, PATH=str(commands) + os.pathsep + os.defpath,
                       DORIS_OUTPUT=str(output), DOCKER=str(docker), BAKE_CALLS=str(calls),
                       BAKE_STAGE_DIR=str(root / "stage"))
            process = subprocess.run(["bash", str(SOURCE / "bake-images.sh"), "--be"],
                                     text=True, capture_output=True, env=env)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn("BE JDBC plugin lacks DuckDbTypeHandler", process.stderr)
            self.assertFalse(calls.exists(), "Docker build must not observe an unpatched BE")


if __name__ == "__main__":
    unittest.main()
