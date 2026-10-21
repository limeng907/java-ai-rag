"""Archive only the V8.8 suites executed in this run, excluding stale live-model reports."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

names = ['AgentRuntimeTest','ToolPolicyTest','AgentEvidenceValidatorTest','MemoryContextTest',
         'InterviewPolicyTest','V8PostgresTest','V88GroundingPostgresTest']
out = Path('docs/testing/v88-evidence')
out.mkdir(parents=True, exist_ok=True)
suites = []
for name in names:
    source = Path('target/surefire-reports') / f'TEST-com.shtbs.interview.agent.{name}.xml'
    tree = ET.parse(source)
    root = tree.getroot()
    suites.append({k:root.attrib[k] for k in ('name','tests','failures','errors','skipped','time')})
    for properties in root.findall('properties'):
        root.remove(properties)
    tree.write(out/source.name, encoding='utf-8', xml_declaration=True)
for source in Path('target/v88-evidence').glob('*.json'):
    shutil.copyfile(source, out/source.name)
shutil.copyfile('target/v88-acceptance-run.log', out/'acceptance-run.log')
shutil.copyfile('target/v88-targeted-run.log', out/'initial-targeted-run.log')
sources = [str(p).replace('\\','/') for p in Path('src/main/java/com/shtbs/interview/agent').rglob('*.java')]
sources += [str(p).replace('\\','/') for p in Path('src/test/java/com/shtbs/interview/agent').glob('*.java')]
sources += ['src/main/java/com/shtbs/interview/knowledge/KnowledgeRepository.java',
            'src/main/java/com/shtbs/interview/knowledge/KnowledgeDocumentService.java',
            'db/postgresql/005_agent_grounding.sql','pom.xml']
totals = {key:sum(int(s[key]) for s in suites) for key in ('tests','failures','errors','skipped')}
manifest = {'totals':totals,'suites':suites,'sha256':{p:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sources},
            'scope':'Current source; mock ChatModel and vector candidate retrieval; real PostgreSQL transactions; no live model/HTTP rerun.'}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
assert totals['failures']==totals['errors']==totals['skipped']==0, totals
print(json.dumps({'totals':totals,'suites':suites},indent=2))
