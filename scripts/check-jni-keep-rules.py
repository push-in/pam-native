#!/usr/bin/env python3
"""Fails when a Java/Kotlin method the Android JNI bridge looks up by name has
no keep rule in android/app/proguard-rules.pro.

R8 renames or removes methods that only native code calls, so GetMethodID
returns null in release builds while debug builds (no R8) keep working.
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
cpp_dir = root / "android/app/src/main/cpp"
rules = (root / "android/app/proguard-rules.pro").read_text()

lookup = re.compile(r'Get(?:Static)?MethodID\(\s*[^,]+,\s*"([A-Za-z_][A-Za-z0-9_]*)"', re.S)
names = set()
for source in cpp_dir.glob("*.cpp"):
    names.update(lookup.findall(source.read_text()))
# Framework classes (java.*, android.*) are never minified.
names.discard("<init>")

missing = sorted(name for name in names if not re.search(rf"\b{re.escape(name)}\s*\(", rules))
framework = {"toString", "getClass", "getName", "hashCode", "equals"}
missing = [name for name in missing if name not in framework]
if missing:
    print("JNI callbacks without a proguard keep rule: " + ", ".join(missing))
    sys.exit(1)
print(f"JNI keep rules cover {len(names)} looked-up methods")
