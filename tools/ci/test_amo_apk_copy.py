"""amo-apk-copy.sh on fake files (D-AMO-MAN-16): the owner-named copy exists, is byte-identical, and SHA256SUMS lists it."""
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("amo-apk-copy.sh")


def run(*args):
    return subprocess.run(["bash", str(SCRIPT), *map(str, args)], capture_output=True, text=True)


class AmoApkCopyTest(unittest.TestCase):
    def test_copy_name_and_checksums(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            for app, body in (("sr", b"sr"), ("amo", b"amo-bytes"), ("tso", b"tso")):
                (d / f"aron-{app}-1.4.2-dev.apk").write_bytes(body)
            r = run(d, "1.4.2", "142", "2026-11-03")
            self.assertEqual(r.returncode, 0, r.stderr)
            name = "aron_amo_app_03_11_2026_v142.apk"
            self.assertEqual(r.stdout.strip(), name)
            self.assertEqual((d / name).read_bytes(), b"amo-bytes")
            sums = (d / "SHA256SUMS").read_text().splitlines()
            self.assertEqual(len(sums), 4)
            self.assertIn(f"{hashlib.sha256(b'amo-bytes').hexdigest()}  ./{name}", sums)
            self.assertIn(f"{hashlib.sha256(b'amo-bytes').hexdigest()}  ./aron-amo-1.4.2-dev.apk", sums)

    def test_missing_amo_apk_fails(self):
        with tempfile.TemporaryDirectory() as d:
            r = run(d, "1.4.2", "142", "2026-11-03")
            self.assertNotEqual(r.returncode, 0)
            self.assertFalse(list(Path(d).glob("aron_amo_app_*")))

    def test_bad_version_code_fails(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "aron-amo-1.4.2-dev.apk").write_bytes(b"x")
            self.assertNotEqual(run(d, "1.4.2", "0", "2026-11-03").returncode, 0)


if __name__ == "__main__":
    unittest.main()
