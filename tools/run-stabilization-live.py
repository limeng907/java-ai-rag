"""Isolated local acceptance runner. Secrets are inherited/read locally, never written to evidence.

Usage: python tools/run-stabilization-live.py prepare|start|check|stop
Requires dedicated ai-java-stabilization-* containers and a packaged target JAR.
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.stabilization-runtime'
EVIDENCE = ROOT / 'docs/testing/v8-stabilization-evidence'
BASE = 'http://localhost:18202'
PG = 'ai-java-stabilization-pg'


def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', PG, 'psql', '-XAtq', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'stabilization'],
                            input=statement.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return result.stdout.decode('utf-8').strip()


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def prepare():
    if not sql("SELECT to_regclass('public.app_user');"):
        for path in sorted((ROOT / 'db/postgresql').glob('00*.sql')):
            sql(path.read_text(encoding='utf-8'))
    sql('CREATE EXTENSION IF NOT EXISTS pgcrypto;')
    print('Dedicated test database initialized.', flush=True)


def environment():
    env = os.environ.copy()
    local = ROOT / '.local-secrets.yml'
    if local.exists():
        for line in local.read_text(encoding='utf-8').splitlines():
            match = re.match(r'^([A-Z][A-Z0-9_]*):\s*(.*?)\s*$', line)
            if match:
                env.setdefault(match[1], match[2].strip("'\""))
    idea = ROOT / '.idea/workspace.xml'
    if idea.exists():
        for item in ET.parse(idea).iter():
            if item.get('name') in ('AI_API_KEY', 'QQ_MAIL_AUTH_CODE') and item.get('value'):
                env.setdefault(item.get('name'), item.get('value'))
    if not env.get('AI_API_KEY'):
        raise RuntimeError('AI_API_KEY is unavailable; live model validation cannot run.')
    return env


def start():
    env = environment()
    env.update({
        'SERVER_PORT': '18202', 'SPRING_DATASOURCE_URL': 'jdbc:postgresql://localhost:55441/stabilization?connectTimeout=10&socketTimeout=30',
        'SPRING_DATASOURCE_PASSWORD': 'stabilization-test-only', 'SPRING_DATA_REDIS_PORT': '56382',
        'STORAGE_S3_ENDPOINT': 'http://localhost:59000', 'STORAGE_S3_BUCKET': 'stabilization-live',
        'STORAGE_S3_ACCESS_KEY': 'stabilization-test', 'STORAGE_S3_SECRET_KEY': 'stabilization-test-secret',
        'JWT_SECRET': uuid.uuid4().hex + uuid.uuid4().hex,
        'AGENT_MEMORY_RECOVERY_DELAY_MS': '1000', 'QQ_MAIL_AUTH_CODE': env.get('QQ_MAIL_AUTH_CODE', 'unused-test-only'),
    })
    RUNTIME.mkdir(exist_ok=True)
    jar = RUNTIME / 'app.jar'
    shutil.copy2(ROOT / 'target/Ai-Java-Interview-1.0-SNAPSHOT.jar', jar)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    with (RUNTIME / 'app.out.log').open('wb') as output:
        proc = subprocess.Popen(['java', '-jar', str(jar)], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
    (RUNTIME / 'process.json').write_text(json.dumps({'pid': proc.pid, 'jar': str(jar), 'url': BASE}), encoding='utf-8')
    print(f'Isolated application started: PID={proc.pid}, URL={BASE}', flush=True)


def docker_start():
    env = environment()
    env.update({
        'DEMO_POSTGRES_PASSWORD': 'stabilization-test-only', 'DEMO_S3_ACCESS_KEY': 'stabilization-test',
        'DEMO_S3_SECRET_KEY': 'stabilization-test-secret', 'DEMO_JWT_SECRET': uuid.uuid4().hex + uuid.uuid4().hex,
        'SPRING_DATASOURCE_URL': 'jdbc:postgresql://host.docker.internal:55441/stabilization?connectTimeout=10&socketTimeout=30',
        'SPRING_DATASOURCE_PASSWORD': 'stabilization-test-only', 'SPRING_DATA_REDIS_HOST': 'host.docker.internal',
        'SPRING_DATA_REDIS_PORT': '56382', 'SPRING_AI_OLLAMA_BASE_URL': 'http://host.docker.internal:11434',
        'SPRING_AI_OPENAI_API_KEY': env['AI_API_KEY'], 'STORAGE_S3_ENDPOINT': 'http://host.docker.internal:59000',
        'STORAGE_S3_BUCKET': 'stabilization-live', 'STORAGE_S3_ACCESS_KEY': 'stabilization-test',
        'STORAGE_S3_SECRET_KEY': 'stabilization-test-secret', 'JWT_SECRET': uuid.uuid4().hex + uuid.uuid4().hex,
        'QQ_MAIL_AUTH_CODE': env.get('QQ_MAIL_AUTH_CODE', 'unused-test-only'), 'AGENT_MEMORY_RECOVERY_DELAY_MS': '1000',
    })
    subprocess.run(['docker', 'compose', 'config', '--quiet'], env=env, check=True)
    with (EVIDENCE / 'docker-build.log').open('wb') as log:
        subprocess.run(['docker', 'build', '-t', 'ai-java-interview:stabilization', '.'], env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    existing = subprocess.run(['docker', 'inspect', '--format', '{{.Config.Image}} {{(index (index .HostConfig.PortBindings "8200/tcp") 0).HostPort}}',
                               'ai-java-stabilization-app'], env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if existing.returncode == 0:
        if existing.stdout.decode().strip() != 'ai-java-interview:stabilization 18203':
            raise RuntimeError('Refusing to replace an unexpected Docker container')
        subprocess.run(['docker', 'rm', '-f', 'ai-java-stabilization-app'], env=env, check=True, stdout=subprocess.DEVNULL)
    command = ['docker', 'run', '-d', '--name', 'ai-java-stabilization-app', '-p', '127.0.0.1:18203:8200']
    for name in env:
        if name.startswith(('SPRING_', 'STORAGE_')) or name in ('JWT_SECRET', 'QQ_MAIL_AUTH_CODE', 'AGENT_MEMORY_RECOVERY_DELAY_MS'):
            command += ['-e', name]
    command += ['ai-java-interview:stabilization']
    subprocess.run(command, env=env, check=True)
    print('Docker application started on http://localhost:18203', flush=True)


def request(method, path, token=None, data=None, raw=None, content_type=None, expected=200):
    body = raw if raw is not None else None if data is None else json.dumps(data, ensure_ascii=False).encode('utf-8')
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if body is not None:
        headers['Content-Type'] = content_type or 'application/json'
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=100) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as exc:
        status, payload = exc.code, exc.read()
    if status != expected:
        # Never include a login response or authorization header in an error.
        raise AssertionError(f'{method} {path}: HTTP {status}, expected {expected}')
    result = json.loads(payload) if payload else {}
    if expected == 200 and result.get('code') != 200:
        raise AssertionError(f'{method} {path}: application code {result.get("code")}')
    return result.get('data')


def wait_for(probe, seconds=70):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        value = probe()
        if value:
            return value
        time.sleep(0.5)
    raise AssertionError('Timed out waiting for acceptance condition')


def check():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    report = {'cases': [], 'executions': [], 'summaries': [], 'failures': []}
    users, tokens, conversations, documents, bases = [], [], [], [], []
    def save():
        (EVIDENCE / 'live-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    def passed(name, **details):
        report['cases'].append({'name': name, 'passed': True, **details})
        save()
        print('PASS ' + name, flush=True)
    def chat(token, data):
        start_time = time.monotonic()
        answer = request('POST', '/agent/chat', token, data)
        execution = request('GET', '/agent/executions/' + answer['executionId'], token)
        report['executions'].append({'executionId': answer['executionId'], 'elapsedSeconds': round(time.monotonic() - start_time, 3),
                                     'modelCalls': execution['model_calls'], 'toolCalls': execution['tool_calls'], 'state': execution['state']})
        if answer['conversationId'] not in conversations:
            conversations.append(answer['conversationId'])
        return answer
    try:
        for index in range(2):
            username = 'stabilization_' + uuid.uuid4().hex[:12]
            password = uuid.uuid4().hex
            user = int(sql(f"INSERT INTO app_user(username,password) VALUES({literal(username)},crypt({literal(password)},gen_salt('bf'))) RETURNING id;"))
            users.append(user)
            tokens.append(request('POST', '/user/login', data={'username': username, 'password': password})['token'])
        token = tokens[0]
        base = request('POST', '/knowledge-bases', token, {'name': 'Live RAG acceptance', 'description': 'Synthetic isolated data'})['id']
        bases.append(base)
        text = '# HashMap resize\n\nHashMap doubles its table capacity when size exceeds the load factor threshold. The acceptance marker is CAPACITY_DOUBLES_42.\n'
        boundary = uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="Java-live.md"\r\nContent-Type: text/markdown\r\n\r\n{text}\r\n--{boundary}--\r\n').encode('utf-8')
        doc = request('POST', f'/knowledge-bases/{base}/documents', token, raw=body, content_type='multipart/form-data; boundary=' + boundary)
        documents.append(doc['id'])
        wait_for(lambda: request('GET', f'/knowledge/documents/{doc["id"]}', token)['status'] == 'READY')
        answer = chat(token, {'message': '请调用searchKnowledge检索HashMap resize，说明扩容条件并给出资料中的验收标记，必须引用检索证据。', 'knowledgeBaseIds': [base]})
        citations = answer['result']['citations']
        assert citations and all(c['documentId'] == doc['id'] and c['version'] == 1 for c in citations), 'Missing real document citation'
        assert 'CAPACITY_DOUBLES_42' in answer['result']['message'], 'Model did not recover the uploaded marker'
        passed('real-http-upload-worker-embedding-retrieval-deepseek-citation', result=answer['result'])
        request('POST', '/knowledge/search', tokens[1], {'knowledgeBaseId': base, 'question': 'HashMap'}, expected=404)
        request('GET', '/agent/conversations/' + answer['conversationId'] + '/messages', tokens[1], expected=404)
        passed('live-cross-user-isolation')
        unavailable = chat(token, {'message': '只查询所选资料：星河公司今年录用了多少人？资料没有就明确说明无法确认，不要猜测数字。', 'knowledgeBaseIds': [base]})
        assert any(word in unavailable['result']['message'] for word in ['无法', '没有', '不足', '未提供', '未提及']), 'Expected an explicit insufficient-information answer'
        passed('real-model-no-answer-sample', message=unavailable['result']['message'])
        statement = chat(token, {'message': '我每天只有45分钟'})
        assert report['executions'][-1]['modelCalls'] == 0 and report['executions'][-1]['toolCalls'] == 0, 'Simple explicit statement used the model/tool loop'
        recalled = chat(token, {'conversationId': statement['conversationId'], 'message': '我之前说每天能学习多少分钟？'})
        assert recalled['memoryFact']['status'] == 'FOUND' and recalled['memoryFact']['value'] == '45'
        assert report['executions'][-1]['modelCalls'] == 0
        unknown = chat(token, {'conversationId': statement['conversationId'], 'message': '请核对公司', 'memoryField': 'INTERVIEW_COMPANY'})
        assert unknown['memoryFact']['status'] == 'NOT_FOUND' and unknown['memoryFact']['value'] is None
        assert report['executions'][-1]['modelCalls'] == 0
        passed('live-exact-memory-found-and-unknown', found=recalled['memoryFact'], unknown=unknown['memoryFact'])
        for label in ['松树计划', '枫树计划', '雪松计划']:
            conversation = str(uuid.uuid4());conversations.append(conversation)
            sql(f"INSERT INTO agent_conversation(id,user_id,kind) VALUES({literal(conversation)},{users[0]},'CHAT');")
            def seed(begin, end):
                statements = ['BEGIN;']
                for turn in range(begin, end + 1):
                    execution = str(uuid.uuid4())
                    user_text = '我每天只有45分钟' if turn == 1 else '我现在每天只能学习30分钟' if turn == 6 else f'我的学习计划名为{label}，目标是Java后端实习，偏好通过项目实践学习Spring Boot。' if turn == 2 else f'继续讨论Spring Boot事务，第{turn}轮。'
                    assistant = json.dumps({'message': '助手建议可以每天学习90分钟，但这不是用户的时间承诺。', 'weaknesses': [], 'plan': [], 'sources': []}, ensure_ascii=False)
                    statements.append(f"INSERT INTO agent_execution(id,user_id,conversation_id,kind,state,prompt_version,committed_at) VALUES({literal(execution)},{users[0]},{literal(conversation)},'CHAT','FINISHED',1,clock_timestamp());")
                    statements.append(f"INSERT INTO agent_message(conversation_id,execution_id,role,content) VALUES({literal(conversation)},{literal(execution)},'USER',{literal(user_text)}),({literal(conversation)},{literal(execution)},'ASSISTANT',{literal(assistant)});")
                # Advance only this synthetic conversation's retry clock to keep the scenario deterministic.
                statements.append(f"UPDATE agent_conversation SET memory_retry_after=clock_timestamp() WHERE id={literal(conversation)};")
                statements.append('COMMIT;');sql('\n'.join(statements))
            for version, begin, end, minutes in [(1, 1, 10, '45'), (2, 11, 15, '30'), (3, 16, 20, '30')]:
                seed(begin, end)
                row = wait_for(lambda: sql(f"SELECT json_build_object('version',version,'summary',summary::json,'boundary',last_message_id) FROM agent_memory_summary WHERE conversation_id={literal(conversation)} AND version>={version};"))
                summary = json.loads(row)
                report['summaries'].append({'case': label, **summary})
                facts = json.dumps(summary['summary']['userStatements'] + summary['summary']['preferences'], ensure_ascii=False)
                assert minutes in facts and '90' not in facts, f'Budget/role failure in {label} version {version}'
                if version > 1:
                    assert '45' not in facts, f'Superseded budget survived in {label}'
                assert label in facts and '后端' in facts and ('项目' in facts or '实践' in facts), f'Goal or preference omitted in {label}'
                passed(f'incremental-summary-{label}-v{version}', expectedDailyMinutes=int(minutes))
        request('DELETE', f'/knowledge/documents/{documents[0]}', token)
        wait_for(lambda: sql(f"SELECT status FROM knowledge_document WHERE id={documents[0]};") == 'DELETED')
        assert request('POST', '/knowledge/search', token, {'knowledgeBaseId': base, 'question': 'HashMap'}) == []
        passed('live-document-deletion-removes-retrieval')
    except Exception as exc:
        report['failures'].append({'type': type(exc).__name__, 'message': str(exc)})
        raise
    finally:
        for conversation in conversations:
            try:
                request('DELETE', '/agent/conversations/' + conversation, tokens[0])
            except Exception:
                pass
        for document in documents:
            try:
                status = sql(f'SELECT status FROM knowledge_document WHERE id={document};')
                if status != 'DELETED':
                    request('DELETE', f'/knowledge/documents/{document}', tokens[0])
                    wait_for(lambda: sql(f'SELECT status FROM knowledge_document WHERE id={document};') == 'DELETED')
            except Exception as exc:
                report['failures'].append({'type': 'Cleanup', 'message': str(exc)})
        for base in bases:
            try:
                request('DELETE', f'/knowledge-bases/{base}', tokens[0])
            except Exception as exc:
                report['failures'].append({'type': 'Cleanup', 'message': str(exc)})
        for user, token in zip(users, tokens):
            try:
                request('POST', '/user/logout', token)
                sql(f'DELETE FROM agent_memory_cache_cleanup WHERE user_id={user}; DELETE FROM user_learning_profile WHERE user_id={user}; DELETE FROM app_user WHERE id={user};')
            except Exception as exc:
                report['failures'].append({'type': 'Cleanup', 'message': str(exc)})
        save()


def stop():
    info = json.loads((RUNTIME / 'process.json').read_text(encoding='utf-8'))
    # Validate ownership before stopping the exact helper process we started.
    expected = str(RUNTIME / 'app.jar')
    if info['jar'] != expected:
        raise RuntimeError('Unexpected application identity')
    if os.name == 'nt':
        command = f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={int(info['pid'])}'; if ($p -and $p.CommandLine -like '*{expected}*') {{ Stop-Process -Id {int(info['pid'])} }}"
        subprocess.run(['powershell', '-NoProfile', '-Command', command], check=True)
    print('Isolated application stopped.', flush=True)


if __name__ == '__main__':
    os.chdir(ROOT)
    if sys.argv[1] == 'check-docker':
        BASE = 'http://localhost:18203'
        check()
    else:
        {'prepare': prepare, 'start': start, 'check': check, 'stop': stop, 'docker-start': docker_start}[sys.argv[1]]()
