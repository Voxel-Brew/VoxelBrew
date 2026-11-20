import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest


CHECKER = Path(__file__).resolve().parents[1] / "gradle" / "JavaMemberOrder.java"
JAVA = os.environ.get("VOXELBREW_MEMBER_ORDER_JAVA", "java")


class JavaMemberOrderTest(unittest.TestCase):
    def test_requires_member_order_without_exemptions(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            source = root / "src" / "Probe.java"
            source.write_text(
                """class Probe {
    static final int CONSTANT = 1;
    static int shared;
    final int fixed = CONSTANT;
    int value;
    static class Nested {}
    class Inner {}
    static Probe factory() { return new Probe(); }
    Probe() {}
    void use() {}
}
""",
                encoding="utf-8",
            )
            ordered = self.run_checker(root)
            self.assertEqual(ordered.returncode, 0, ordered.stderr)

            source.write_text(
                """class Probe {
    Probe() {}
    static Probe factory() { return new Probe(); }
}
""",
                encoding="utf-8",
            )
            changed = self.run_checker(root)
            self.assertEqual(changed.returncode, 1, changed.stderr)
            self.assertIn(
                "src/Probe.java:3: static method 'factory()' must appear before constructor",
                changed.stderr,
            )

    def run_checker(self, root):
        return subprocess.run(
            [JAVA, str(CHECKER), "src"],
            cwd=root,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
