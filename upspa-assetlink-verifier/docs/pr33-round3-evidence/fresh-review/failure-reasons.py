"""Lists every test case of one JUnit XML report as PASS or FAIL, with the failure message.

Usage: python -I failure-reasons.py REPORT.xml OUT.txt
"""
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as out:
    out.write(f"{root.get('name')} tests={root.get('tests')} skipped={root.get('skipped')} "
              f"failures={root.get('failures')} errors={root.get('errors')}\n")
    for case in sorted(root.findall("testcase"), key=lambda c: c.get("name")):
        failure = case.find("failure")
        out.write(("FAIL " if failure is not None else "PASS ") + case.get("name") + "\n")
        if failure is not None:
            out.write("    " + failure.get("message") + "\n")
