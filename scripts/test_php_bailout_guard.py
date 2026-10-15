"""Contract: no PHP code runs on the bridge worker without a bailout address.

A PHP fatal error or exit() outside a zend_try made zend_bailout() call
exit(-1): the app process died with code 255 (and C++ static destructors ran
under the live render thread). Android and iOS must run every PHP entry point
of run_php_request() inside run_guarded() and share the same guard code.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRIDGES = {
    "android": ROOT / "android/app/src/main/cpp/pam_android_bridge.cpp",
    "ios": ROOT / "ios/Sources/PamNative/Bridge/pam_native_ios_bridge.cpp",
}
# Calls that execute PHP code (handlers, renders, destructors).
PHP_CALLS = ("call_runtime(", "dispatch_event(", "gc_collect_cycles(", "enable_deferred_rendering(")


def matching_brace(text: str, start: int) -> int:
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("unbalanced braces")


def function_body(text: str, signature: str) -> str:
    start = text.index(signature)
    open_brace = text.index("{", start)
    return text[open_brace:matching_brace(text, open_brace) + 1]


def guarded_ranges(body: str) -> list[tuple[int, int]]:
    ranges = []
    for match in re.finditer(r"run_guarded\(state, \"[^\"]+\", \[&\] \{", body):
        open_brace = match.end() - 1
        ranges.append((open_brace, matching_brace(body, open_brace)))
    return ranges


def unguarded_php_calls(body: str) -> list[str]:
    ranges = guarded_ranges(body)
    problems = []
    for call in PHP_CALLS:
        for match in re.finditer(re.escape(call), body):
            if not any(start < match.start() < end for start, end in ranges):
                line = body[:match.start()].count("\n") + 1
                problems.append(f"{call} at line {line} of run_php_request")
    return problems


def guard_section(text: str) -> str:
    start = text.index("enum class RequestOutcome")
    end = text.index("RequestOutcome run_php_request(RuntimeState* state)")
    without_comments = re.sub(r"//[^\n]*", "", text[start:end])
    return "\n".join(line.strip() for line in without_comments.splitlines() if line.strip())


class PhpBailoutGuardTests(unittest.TestCase):
    def test_every_php_call_of_the_worker_is_guarded(self):
        for platform, path in BRIDGES.items():
            with self.subTest(platform=platform):
                body = function_body(path.read_text(encoding="utf-8"), "RequestOutcome run_php_request(")
                self.assertTrue(guarded_ranges(body), "run_php_request has no guarded call")
                self.assertEqual([], unguarded_php_calls(body))

    def test_entry_bailouts_end_the_request(self):
        for platform, path in BRIDGES.items():
            with self.subTest(platform=platform):
                body = function_body(path.read_text(encoding="utf-8"), "RequestOutcome run_php_request(")
                self.assertIn("CG(unclean_shutdown)", body)
                self.assertIn('report_bailout(state, "the app entry"', body)
                self.assertNotIn("const int status = php_execute_script", body)

    def test_failed_requests_wait_for_a_reload_and_never_exit(self):
        for platform, path in BRIDGES.items():
            with self.subTest(platform=platform):
                text = path.read_text(encoding="utf-8")
                loop = function_body(text, "void runtime_loop(RuntimeState* state)")
                self.assertIn("RequestOutcome::Failed", loop)
                self.assertIn("await_reload(state)", loop)
                code = re.sub(r'"(?:\\.|[^"\\])*"', '""', re.sub(r"//[^\n]*", "", text))
                found = re.search(r"\b(?:exit|_exit|abort)\(", code)
                self.assertIsNone(found and found.group(0), "the bridge must never end the process")

    def test_android_and_ios_share_the_guard(self):
        sections = {platform: guard_section(path.read_text(encoding="utf-8")) for platform, path in BRIDGES.items()}
        self.assertEqual(sections["android"], sections["ios"])

    def test_detector_rejects_an_unguarded_call(self):
        body = '{\n    run_guarded(state, "x", [&] {\n        call_runtime("flush", 0, nullptr);\n    });\n    gc_collect_cycles();\n}'
        self.assertEqual(["gc_collect_cycles( at line 5 of run_php_request"], unguarded_php_calls(body))


if __name__ == "__main__":
    unittest.main()
