#!/usr/bin/env python3
"""Self-test of tools/ci/gradle-lockfile.py (N-061; owner qa). Offline: python3 tools/ci/test_gradle_lockfile.py"""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("gradlelock", Path(__file__).resolve().parent / "gradle-lockfile.py")
gradlelock = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gradlelock)

TREE = r"""
runtimeClasspath - Runtime classpath of source set 'main'.
+--- project :backend:platform
|    +--- io.ktor:ktor-server-auth-jwt -> 3.6.0
|    |    \--- com.auth0:java-jwt:4.6.0
|    |         +--- com.fasterxml.jackson.core:jackson-core:2.22.0
|    |         |    \--- com.fasterxml.jackson:jackson-bom:2.22.0
|    |         |         +--- com.fasterxml.jackson.core:jackson-annotations:2.22 (c)
|    |         |         \--- com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.22.0 (c)
|    |         \--- com.fasterxml.jackson.core:jackson-databind:2.18.3 -> 2.22.0 (*)
|    +--- org.jetbrains.kotlin:kotlin-stdlib:{prefer 2.1.0} -> 2.4.20
|    +--- org.example:strict:{strictly 1.4}
|    \--- org.example:declared-only:2.0 (n)
\--- org.postgresql:postgresql:42.7.8
"""


class Modules(unittest.TestCase):
    def test_resolved_versions_only(self):
        self.assertEqual(
            {
                "io.ktor:ktor-server-auth-jwt:3.6.0",
                "com.auth0:java-jwt:4.6.0",
                "com.fasterxml.jackson.core:jackson-core:2.22.0",
                "com.fasterxml.jackson:jackson-bom:2.22.0",
                "com.fasterxml.jackson.core:jackson-databind:2.22.0",
                "org.jetbrains.kotlin:kotlin-stdlib:2.4.20",
                "org.example:strict:1.4",
                "org.postgresql:postgresql:42.7.8",
            },
            gradlelock.modules(TREE),
        )

    def test_a_failed_module_or_an_open_range_stops_the_run(self):
        for bad in ("+--- org.example:missing:1.0 FAILED", "+--- org.example:ranged:{strictly [1.0, 2.0[}", "+--- org.example:plus:1.+"):
            with self.assertRaises(gradlelock.Unresolved, msg=bad):
                gradlelock.modules(TREE + bad + "\n")
        self.assertIn("org.example:ranged:1.4", gradlelock.modules("+--- org.example:ranged:[1.0,2.0) -> 1.4\n"))

    def test_writes_a_lockfile_and_refuses_an_empty_tree(self):
        with tempfile.TemporaryDirectory() as d:
            tree, empty, out = Path(d) / "t.txt", Path(d) / "e.txt", Path(d) / "x" / "gradle.lockfile"
            tree.write_text(TREE, encoding="utf-8")
            empty.write_text("> Task :dependencies FAILED\n", encoding="utf-8")
            self.assertEqual(0, gradlelock.main([str(out), str(tree)]))
            lines = out.read_text(encoding="utf-8").splitlines()
            self.assertIn("com.fasterxml.jackson.core:jackson-databind:2.22.0=runtimeClasspath", lines)
            self.assertEqual(sorted(lines), lines)
            self.assertEqual(2, gradlelock.main([str(out), str(tree), str(empty)]))


if __name__ == "__main__":
    unittest.main()
