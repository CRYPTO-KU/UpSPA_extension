"""Replaces exactly one occurrence of OLD with NEW in FILE (exact string match, LF or CRLF tolerant).

Usage: python -I mutate.py FILE OLD NEW
OLD and NEW use the two-character sequence \\n for a newline.
"""
import sys

path, old, new = sys.argv[1], sys.argv[2].replace("\\n", "\n"), sys.argv[3].replace("\\n", "\n")
with open(path, encoding="utf-8", newline="") as f:
    text = f.read()
crlf = "\r\n" in text
if crlf:
    text = text.replace("\r\n", "\n")
count = text.count(old)
if count != 1:
    sys.exit(f"mutate.py: expected exactly 1 occurrence in {path}, found {count}: {old!r}")
text = text.replace(old, new)
if crlf:
    text = text.replace("\n", "\r\n")
with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)
