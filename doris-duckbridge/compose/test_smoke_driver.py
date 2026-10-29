"""Cluster-free checks for smoke.sh command orchestration."""

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


SOURCE = Path(__file__).resolve().parent


class SmokeDriverTests(unittest.TestCase):
    def test_smoke_rejects_mismatched_fe_be_before_catalog_creation(self):
        self.assert_rejected_versions("doris-0.0.0-082736138b7", "doris-0.0.0-stale-be")

    def test_smoke_rejects_matched_but_wrong_source_before_catalog_creation(self):
        self.assert_rejected_versions("doris-0.0.0-62e3d81a42b", "doris-0.0.0-62e3d81a42b")

    def assert_rejected_versions(self, fe_version, be_version):
        with tempfile.TemporaryDirectory(prefix="duckbridge-version-") as temporary:
            root = Path(temporary)
            compose = root / "doris-duckbridge" / "compose"
            compose.mkdir(parents=True)
            for name in ("smoke.sh", "fe.conf"):
                shutil.copyfile(SOURCE / name, compose / name)
            (compose / ".env").write_text("QUACK_TOKEN=test-token\n")
            (compose / "docker-compose.yml").write_text("services: {}\n")
            baseline = root / "doris-patches" / "BASELINE"
            baseline.parent.mkdir()
            shutil.copyfile(SOURCE.parents[1] / "doris-patches" / "BASELINE", baseline)
            distributions = compose.parent / "build" / "distributions"
            distributions.mkdir(parents=True)
            with zipfile.ZipFile(distributions / "doris-duckbridge-test-plugin.zip", "w") as archive:
                archive.writestr("lib/quack-jdbc-0.11.0.jar", b"driver")

            fake_docker = root / "docker"
            fake_docker.write_text(
                """#!/bin/sh
set -eu
if [ "$1" = "create" ]; then echo helper; exit 0; fi
if [ "$1" = "exec" ] && [ "$2" = "doris-duckbridge-fe" ] && [ "$3" = "mysql" ]; then
  sql=""
  no_headers=0
  while [ "$#" -gt 0 ]; do
    [ "$1" = "-N" ] && no_headers=1
    if [ "$1" = "-e" ]; then shift; sql="$1"; break; fi
    shift
  done
  case "$sql" in
    *"SHOW FRONTENDS"*)
      if [ "$no_headers" -eq 1 ]; then
        printf 'fe1\t172.31.80.10\t0\t0\t0\t0\t0\t0\t0\ttrue\t%s\n' "$FAKE_FE_VERSION"
      else
        printf 'Name\tHost\tc3\tc4\tc5\tc6\tc7\tc8\tc9\tAlive\tVersion\n'
        printf 'fe1\t172.31.80.10\t0\t0\t0\t0\t0\t0\t0\ttrue\t%s\n' "$FAKE_FE_VERSION"
      fi
      ;;
    *"SHOW BACKENDS"*)
      if [ "$no_headers" -eq 1 ]; then
        printf 'be1\t172.31.80.20\t0\t0\t0\t0\t0\t0\t0\ttrue\t%s\n' "$FAKE_BE_VERSION"
      else
        printf 'Name\tHost\tc3\tc4\tc5\tc6\tc7\tc8\tc9\tAlive\tVersion\n'
        printf 'be1\t172.31.80.20\t0\t0\t0\t0\t0\t0\t0\ttrue\t%s\n' "$FAKE_BE_VERSION"
      fi
      ;;
  esac
  exit 0
fi
if [ "$1" = "exec" ] && [ "$2" = "doris-duckbridge-quack" ]; then
  echo v2.0.0
  exit 0
fi
exit 0
"""
            )
            fake_docker.chmod(0o755)
            sleep = root / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n")
            sleep.chmod(0o755)
            env = {
                "PATH": str(root) + os.pathsep + os.defpath,
                "HOME": temporary,
                "DOCKER": str(fake_docker),
                "LC_ALL": "C",
                "FAKE_FE_VERSION": fe_version,
                "FAKE_BE_VERSION": be_version,
            }
            result = subprocess.run(
                ["bash", str(compose / "smoke.sh"), "--no-build", "--up-only"],
                cwd=root,
                env=env,
                text=True,
                capture_output=True,
                timeout=10,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("VERSION FAIL", result.stdout + result.stderr)
            self.assertNotIn("Creating catalog duckbridge_test", result.stdout + result.stderr)

    def test_down_stages_static_fe_election_network(self):
        with tempfile.TemporaryDirectory(prefix="duckbridge-smoke-") as temporary:
            compose = Path(temporary) / "doris-duckbridge" / "compose"
            compose.mkdir(parents=True)
            for name in ("smoke.sh", "fe.conf"):
                shutil.copyfile(SOURCE / name, compose / name)
            (compose / ".env").write_text("QUACK_TOKEN=test-token\n")
            (compose / "docker-compose.yml").write_text("services: {}\n")

            log = Path(temporary) / "docker.log"
            fake_docker = Path(temporary) / "docker"
            fake_docker.write_text(
                "#!/bin/sh\nprintf '%s\\n' "
                + shlex.quote(str(log))
                + ' > /dev/null\nprintf \'%s\\n\' "$*" >> '
                + shlex.quote(str(log))
                + "\n"
            )
            fake_docker.chmod(0o755)
            env = {
                "PATH": os.defpath,
                "HOME": temporary,
                "DOCKER": str(fake_docker),
                "LC_ALL": "C",
            }
            result = subprocess.run(
                ["bash", str(compose / "smoke.sh"), "--down"],
                cwd=temporary,
                env=env,
                text=True,
                capture_output=True,
                timeout=10,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(
                (compose / ".fe.conf.runtime").read_bytes(),
                (compose / "fe.conf").read_bytes()
                + b"\npriority_networks = 172.31.80.0/24\n",
            )
            self.assertEqual(
                log.read_text().splitlines(),
                [f"compose -f {compose / 'docker-compose.yml'} down -v"],
            )


if __name__ == "__main__":
    if sys.version_info < (3, 9):
        raise SystemExit("Python 3.9+ is required")
    unittest.main()
