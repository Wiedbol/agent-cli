"""Replay the recorded points fix against unchanged tests, without a model call."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "examples/repair-lab-v2/control"))
from lab_v2 import inputs

CASE = "dev-points"
SOURCE = "src/main/java/demo/BusinessRules.java"
TEST = "src/test/java/demo/BusinessRulesTest.java"
REPORT = "target/surefire-reports/TEST-demo.BusinessRulesTest.xml"
BEFORE = "2a90eb4337a7977cdffcef4b57d6a176f5563481999a1dcbcf9644da17fc181c"
AFTER = "531b3ce2128c00d30294054725188acaf14d53b39bb237c17f232fa43941c510"
OLD = b"return points > 1200;"
NEW = b"return points >= 1200;"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run_phase(mvn, work, proof, phase, manifest):
    xml_path = work / REPORT
    result = subprocess.run([mvn, "-o", "-B", "clean", "test"], cwd=work,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (proof / f"{phase}.log").write_bytes(result.stdout)
    if not xml_path.is_file():
        raise RuntimeError(f"{phase}: missing Surefire report, see {proof}")
    report = xml_path.read_bytes()
    (proof / f"{phase}.xml").write_bytes(report)
    suite = ET.fromstring(report)
    tests = suite.findall("testcase")
    failures = sorted(test.get("name") for test in tests if test.find("failure") is not None)
    expected = sorted(manifest["expectedFailures"]) if phase == "baseline" else []
    if (sorted(test.get("name") for test in tests) != sorted(manifest["tests"])
            or any(test.get("classname") != "demo.BusinessRulesTest" for test in tests)
            or int(suite.attrib["tests"]) != len(tests)
            or int(suite.attrib["failures"]) != len(failures)
            or any(int(suite.attrib[key]) != 0 for key in ("errors", "skipped"))
            or failures != expected or result.returncode != (1 if expected else 0)):
        raise RuntimeError(f"{phase}: unexpected verdict {failures}, see {proof}")
    for name in ("pom.xml", TEST):
        if sha((work / name).read_bytes()) != manifest["acceptance"][name]:
            raise RuntimeError(f"Frozen input changed: {name}")
    print(f"{phase}: {len(tests)} tests, {len(failures)} failures", flush=True)
    return {"tests": len(tests), "failures": failures, "reportSha256": sha(report)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="new evidence directory")
    parser.add_argument("--repeats", type=int, choices=range(1, 11), default=1)
    args = parser.parse_args()
    mvn = shutil.which("mvn.cmd") or shutil.which("mvn")
    if mvn is None:
        parser.error("Maven must be on PATH")
    catalog = json.loads((REPO / "examples/repair-lab-v2/MANIFEST.json").read_text(encoding="utf-8"))
    manifest = next(case for case in catalog["cases"] if case["id"] == CASE)
    frozen = inputs(CASE)
    if {name: sha(data) for name, data in frozen.items()} != manifest["initial"]:
        raise RuntimeError("Fixture differs from the frozen initial manifest")
    args.output.mkdir()
    summary = {"mode": "OFFLINE_RECORDED_EDIT_REPLAY", "case": CASE, "modelRequests": 0, "runs": []}
    print("OFFLINE RECORDED EDIT REPLAY", flush=True)
    for number in range(1, args.repeats + 1):
        proof = args.output / f"run-{number}"
        proof.mkdir()
        print(f"run {number}/{args.repeats}", flush=True)
        with tempfile.TemporaryDirectory(prefix="demo-points-") as tmp:
            work = Path(tmp)
            for name, data in frozen.items():
                p = work / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(data)
            baseline = run_phase(mvn, work, proof, "baseline", manifest)
            source = work / SOURCE
            before = source.read_bytes()
            after = before.replace(OLD, NEW, 1)
            if sha(before) != BEFORE or before.count(OLD) != 1 or sha(after) != AFTER:
                raise RuntimeError("Source does not match the recorded before/after digests")
            (proof / "before.java").write_bytes(before)
            source.write_bytes(after)
            (proof / "after.java").write_bytes(after)
            print("recorded edit: > 1200 -> >= 1200", flush=True)
            final = run_phase(mvn, work, proof, "final", manifest)
            if sha(source.read_bytes()) != AFTER:
                raise RuntimeError("Source changed during final verification")
            summary["runs"].append({"run": number, "baseline": baseline, "final": final,
                                    "beforeSha256": BEFORE, "afterSha256": AFTER})
            (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
