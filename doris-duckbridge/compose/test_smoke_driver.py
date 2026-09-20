"""Cluster-free checks for smoke.sh command orchestration."""

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent


class SmokeDriverTests(unittest.TestCase):
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
