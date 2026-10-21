"""Archive sanitized V8.9 JUnit results and source hashes for the test report."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

names = [
    "AgentRuntimeTest", "ToolPolicyTest", "AgentEvidenceValidatorTest",
    "MemoryContextTest", "InterviewPolicyTest", "V8PostgresTest",
    "V88GroundingPostgresTest", "V89MemoryLiveTest",
]
out = Path("docs/testing/v89-evidence")
out.mkdir(parents=True, exist_ok=True)
suites = []
for name in names:
    source = Path("target/surefire-reports") / f"TEST-com.shtbs.interview.agent.{name}.xml"
    tree = ET.parse(source)
    root = tree.getroot()
    suites.append({key: root.attrib[key] for key in ("name", "tests", "failures", "errors", "skipped", "time")})
    for properties in root.findall("properties"):
        root.remove(properties)
    tree.write(out / source.name, encoding="utf-8", xml_declaration=True)

for source, destination in (
    ("target/v89-targeted-run.log", "targeted-run.log"),
    ("target/v89-regression-run.log", "regression-run.log"),
    ("target/v89-live-run.log", "live-run.log"),
):
    shutil.copyfile(source, out / destination)

sources = [str(path).replace("\\", "/") for path in Path("src/main/java/com/shtbs/interview/agent").rglob("*.java")]
sources += [str(path).replace("\\", "/") for path in Path("src/test/java/com/shtbs/interview/agent").glob("*.java")]
sources += ["db/postgresql/006_agent_memory.sql", "pom.xml"]
totals = {key: sum(int(suite[key]) for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
manifest = {
    "regression_tests": sum(int(suite["tests"]) for suite in suites[:-1]),
    "live_tests": int(suites[-1]["tests"]),
    "totals": totals,
    "suites": suites,
    "sha256": {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in sources},
    "scope": "Real isolated PostgreSQL/Redis; mocked ChatModel regression; one real DeepSeek HTTP/JWT smoke test.",
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
assert totals == {"tests": 174, "failures": 0, "errors": 0, "skipped": 0}, totals
print(json.dumps({"totals": totals, "suites": suites}, indent=2, ensure_ascii=False))
