"""Collect sanitized final test evidence without adding overlapping test counts."""
import difflib
import hashlib
import json
import pathlib
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/testing/v8-stabilization-evidence'
merged = {}
for folder in ['final-junit', 'additional-junit', 'live-junit']:
    for path in sorted((OUT / folder).glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        for case in suite.findall('testcase'):
            key = (case.get('classname', suite.get('name')), case.get('name'))
            merged[key] = {'class': key[0], 'test': key[1], 'seconds': float(case.get('time', 0)),
                           'status': 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed',
                           'evidence': str(path.relative_to(ROOT)).replace('\\', '/')}
for folder in ['baseline-junit', 'final-junit', 'additional-junit', 'live-junit']:
    for path in (OUT / folder).glob('TEST-*.xml'):
        tree = ET.parse(path)
        for properties in list(tree.getroot().findall('properties')):
            tree.getroot().remove(properties)
        tree.write(path, encoding='utf-8', xml_declaration=True)

changes = []
diff = []
with zipfile.ZipFile(OUT / 'source-before.zip') as before:
    original = set(before.namelist())
    for path in sorted((ROOT / 'src').rglob('*')):
        if not path.is_file():
            continue
        name = path.relative_to(ROOT).as_posix()
        old = before.read(name) if name in original else b''
        new = path.read_bytes()
        if old != new:
            changes.append({'path': name, 'change': 'modified' if name in original else 'added', 'sha256': hashlib.sha256(new).hexdigest()})
            diff.extend(difflib.unified_diff(old.decode('utf-8').splitlines(True), new.decode('utf-8').splitlines(True), fromfile='before/' + name, tofile='after/' + name))
(OUT / 'source-changes.patch').write_text(''.join(diff), encoding='utf-8')
extra = ['Dockerfile', '.dockerignore', '.gitignore', '.env.example', 'compose.yml', 'deploy/healthcheck.sh', 'tools/run-stabilization-live.py', 'tools/collect-stabilization-evidence.py']
hashes = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in extra}
jar = ROOT / 'target/Ai-Java-Interview-1.0-SNAPSHOT.jar'
if jar.exists():
    hashes[jar.relative_to(ROOT).as_posix()] = hashlib.sha256(jar.read_bytes()).hexdigest()
report = {'date': '2026-10-20', 'timezone': 'Asia/Tokyo', 'counting': 'Latest result per (class, method); overlapping runs are not added.',
          'totals': {status: sum(t['status'] == status for t in merged.values()) for status in ['passed', 'failed', 'skipped']},
          'uniqueTests': len(merged), 'testResults': list(merged.values()), 'sourceChanges': changes, 'artifactSha256': hashes}
(OUT / 'manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'totals': report['totals'], 'uniqueTests': report['uniqueTests'], 'changedSourceFiles': len(changes)}))
