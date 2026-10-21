"""Keep reproducible acceptance evidence without JWTs or JVM properties."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

out = Path('docs/testing/v87-evidence')
out.mkdir(parents=True, exist_ok=True)
suites = []
for source in sorted(Path('target/surefire-reports').glob('TEST-*.xml')):
    tree = ET.parse(source)
    root = tree.getroot()
    suites.append({k: root.attrib[k] for k in ('name','tests','failures','errors','skipped','time')})
    for properties in root.findall('properties'):
        root.remove(properties)
    tree.write(out/source.name, encoding='utf-8', xml_declaration=True)

raw = json.loads(Path('target/v87-newman-raw.json').read_text(encoding='utf-8'))
executions = []
for item in raw['run']['executions']:
    response = item.get('response', {})
    data = bytes(response.get('stream', {}).get('data', [])).decode('utf-8')
    try:
        data = json.loads(data)
    except ValueError:
        pass
    if '/user/login' in str(item['request'].get('url')):
        data = {'redacted': 'Login response omitted to remove JWT'}
    executions.append({'name':item['item']['name'], 'method':item['request']['method'],
                       'status':response.get('code'), 'responseTimeMs':response.get('responseTime'),
                       'assertions':item.get('assertions', []), 'response':data})
summary = {'stats':raw['run']['stats'], 'timings':raw['run']['timings'], 'executions':executions}
serialized = json.dumps(summary, ensure_ascii=False, indent=2)
# Defense in depth: omit bearer tokens even if failure output unexpectedly contains one.
import re
serialized = re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]', serialized)
(out/'postman-results.json').write_text(serialized, encoding='utf-8')
for source, name in [('target/v87-acceptance-run.log','acceptance-run.log'),
                     ('target/v87-live-run.log','live-run.log'),
                     ('target/v87-postman-run.log','postman-run.log'),
                     ('target/v87-evidence/live-study-plan.json','live-study-plan.json')]:
    shutil.copyfile(source, out/name)
files = ['src/main/java/com/shtbs/interview/agent/AgentRuntime.java',
         'src/main/java/com/shtbs/interview/agent/AgentService.java',
         'src/main/java/com/shtbs/interview/agent/AgentRepository.java',
         'src/main/resources/application.yml', 'pom.xml']
files += [str(p).replace('\\','/') for p in Path('src/test/java/com/shtbs/interview/agent').glob('*.java')]
totals = {k:sum(int(s[k]) for s in suites) for k in ('tests','failures','errors','skipped')}
manifest = {'junit':totals,'suites':suites,'postman':raw['run']['stats'],
            'sha256':{p:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in files}}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps({'junit':totals,'postman':raw['run']['stats']},indent=2))
