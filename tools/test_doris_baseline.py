"""Cluster-free regression checks for dedicated Doris checkout safety."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent


class DorisBaselineTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="duckbridge-baseline-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "upstream"
        self.source.mkdir()
        self.git(self.source, "init", "-b", "master")
        (self.source / "fixture").write_text("before\n")
        self.git(self.source, "add", "fixture")
        self.git(self.source, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-m", "fixture")
        pin = self.git(self.source, "rev-parse", "HEAD").stdout.strip()
        self.repo = self.root / "duckbridge"
        (self.repo / "tools").mkdir(parents=True)
        shutil.copyfile(SOURCE / "doris-baseline.sh", self.repo / "tools/doris-baseline.sh")
        patches = self.repo / "doris-patches"
        (patches / "be").mkdir(parents=True)
        (patches / "BASELINE").write_text(
            f"PIN_SHA={pin}\nPIN_SUBJECT=fixture\nFORK_URL={self.source}\nFORK_BRANCH=master\n"
        )
        (patches / "be/0001-duckdb-type-handler.patch").write_text(
            "diff --git a/fixture b/fixture\n--- a/fixture\n+++ b/fixture\n"
            "@@ -1 +1,2 @@\n before\n+after\n"
        )

    @staticmethod
    def git(directory, *args):
        return subprocess.run(["git", "-C", str(directory), *args], check=True,
                              text=True, capture_output=True)

    def run_baseline(self, checkout, *args):
        return subprocess.run(["bash", str(self.repo / "tools/doris-baseline.sh"), *args],
                              env={**os.environ, "DORIS_SRC": str(checkout)},
                              text=True, capture_output=True, timeout=30)

    def test_clean_worktree_file_is_supported_without_clone_or_remote_mutation(self):
        checkout = self.root / "worktree"
        self.git(self.source, "worktree", "add", "--detach", str(checkout), "HEAD")
        self.assertTrue((checkout / ".git").is_file())
        before = self.git(self.source, "config", "--local", "--list").stdout
        result = self.run_baseline(checkout, "--check-only")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("cloning fork", result.stdout)
        self.assertEqual((checkout / "fixture").read_text(), "before\n")
        self.assertEqual(self.git(self.source, "config", "--local", "--list").stdout, before)

    def test_dirty_staged_and_untracked_work_survives(self):
        (self.source / "fixture").write_text("human work\n")
        self.git(self.source, "add", "fixture")
        (self.source / "untracked").write_text("keep me\n")
        before = self.git(self.source, "diff", "--cached", "--binary").stdout
        result = self.run_baseline(self.source, "--apply")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no reset or clean was performed", result.stderr)
        self.assertNotIn("fetching", result.stdout)
        self.assertEqual(self.git(self.source, "diff", "--cached", "--binary").stdout, before)
        self.assertEqual((self.source / "untracked").read_text(), "keep me\n")

    def test_second_apply_refuses_to_discard_the_first_patch(self):
        checkout = self.root / "worktree"
        self.git(self.source, "worktree", "add", "--detach", str(checkout), "HEAD")
        first = self.run_baseline(checkout, "--apply")
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        second = self.run_baseline(checkout, "--apply")
        self.assertNotEqual(second.returncode, 0)
        self.assertIn("DORIS_SRC is not clean", second.stderr)
        self.assertEqual((checkout / "fixture").read_text(), "before\nafter\n")


if __name__ == "__main__":
    unittest.main()
