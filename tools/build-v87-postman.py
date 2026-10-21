"""Generate the reusable Postman collection; no real credentials are embedded."""
import json
from pathlib import Path

items = []
def request(name, method, path, status=200, body=None, token='tokenA', tests=''):
    headers = []
    if token:
        headers.append({'key': 'Authorization', 'value': 'Bearer {{'+token+'}}'})
    req = {'method': method, 'header': headers, 'url': '{{baseUrl}}'+path}
    if body is not None:
        headers.append({'key': 'Content-Type', 'value': 'application/json'})
        req['body'] = {'mode': 'raw', 'raw': json.dumps(body, ensure_ascii=False)}
    script = f"pm.test('HTTP {status}', () => pm.response.to.have.status({status}));\n" + tests
    items.append({'name': name, 'request': req, 'event': [{'listen':'test','script':{'type':'text/javascript','exec':script.splitlines()}}]})

request('01 Unauthenticated request', 'POST', '/agent/chat', 401, {'message':'hello'}, None)
for suffix in ('A', 'B'):
    request('02 Login '+suffix, 'POST', '/user/login', body={'username':'{{username'+suffix+'}}','password':'{{password}}'}, token=None,
        tests="const t=pm.response.json().data.token; pm.test('JWT returned',()=>pm.expect(t.split('.')).to.have.length(3)); pm.collectionVariables.set('token"+suffix+"',t);")
request('03 Profile A', 'GET', '/agent/profile', tests="pm.test('120 minutes',()=>pm.expect(pm.response.json().data.dailyMinutes).to.eql(120));")
request('04 Profile B', 'GET', '/agent/profile', token='tokenB', tests="pm.test('60 minutes',()=>pm.expect(pm.response.json().data.dailyMinutes).to.eql(60));")
request('05 Deny B private knowledge to A', 'POST', '/agent/chat', 404, {'message':'HashMap','knowledgeBaseIds':[302]})
request('06 Chat with real tool', 'POST', '/agent/chat', body={'conversationId':None,'message':'请调用 getInterviewHistory 查询最近三次面试，用一句话概括。weaknesses、plan、sources、citations 返回空数组。','knowledgeBaseIds':[]}, tests="""
const d=pm.response.json().data;
pm.test('IDs returned',()=>{pm.expect(d.conversationId).to.be.a('string');pm.expect(d.executionId).to.be.a('string');});
pm.collectionVariables.set('chatConversation',d.conversationId);pm.collectionVariables.set('chatExecution',d.executionId);
""")
request('07 Chat execution audit', 'GET', '/agent/executions/{{chatExecution}}', tests="""
const d=pm.response.json().data;
pm.test('Committed FINISHED',()=>{pm.expect(d.state).to.eql('FINISHED');pm.expect(d.committed_at).to.be.ok;});
pm.test('Actual history tool',()=>pm.expect(d.tools.some(t=>t.tool_name==='getInterviewHistory' && t.status==='SUCCEEDED')).to.eql(true));
""")
request('08 Deny B access to A conversation', 'GET', '/agent/conversations/{{chatConversation}}/messages', 404, token='tokenB')
request('09 Deny B access to A execution', 'GET', '/agent/executions/{{chatExecution}}', 404, token='tokenB')
request('10 Create seven day plan', 'POST', '/agent/study-plans', body={'conversationId':None,'message':'根据我最近三次Java面试的表现，制定7天学习计划，每天最多120分钟。根据真实工具证据生成；不要把跳过当成能力不足。','knowledgeBaseIds':[]}, tests="""
const d=pm.response.json().data;
pm.test('Plan identifiers',()=>{pm.expect(d.studyPlanId).to.be.a('number');pm.expect(d.executionId).to.be.a('string');pm.expect(d.conversationId).to.be.a('string');});
pm.test('Exactly seven numbered days',()=>pm.expect(d.result.plan.map(x=>x.day).sort()).to.eql([1,2,3,4,5,6,7]));
pm.test('Daily budget',()=>d.result.plan.forEach(x=>pm.expect(x.minutes).to.be.within(1,120)));
pm.test('Known session references',()=>d.result.weaknesses.forEach(w=>w.sessionIds.forEach(id=>pm.expect([101,102,103]).to.include(id))));
pm.test('Known question references',()=>d.result.plan.forEach(p=>p.questionIds.forEach(id=>pm.expect([201,202,203]).to.include(id))));
pm.collectionVariables.set('planId',d.studyPlanId);pm.collectionVariables.set('planExecution',d.executionId);pm.collectionVariables.set('planConversation',d.conversationId);
pm.collectionVariables.set('planResult',JSON.stringify(d.result));
""")
request('11 Study execution evidence', 'GET', '/agent/executions/{{planExecution}}', tests="""
const d=pm.response.json().data;
pm.test('Committed FINISHED',()=>{pm.expect(d.state).to.eql('FINISHED');pm.expect(d.committed_at).to.be.ok;});
pm.test('Call budgets',()=>{pm.expect(d.model_calls).to.be.within(1,6);pm.expect(d.tool_calls).to.be.within(3,12);});
pm.test('Required successful tools',()=>{const names=d.tools.filter(t=>t.status==='SUCCEEDED').map(t=>t.tool_name);['getUserProfile','getInterviewHistory','analyzeWeaknesses'].forEach(n=>pm.expect(names).to.include(n));});
pm.test('Unique tool call IDs',()=>pm.expect(new Set(d.tools.map(t=>t.tool_call_id)).size).to.eql(d.tools.length));
pm.test('Weaknesses use actual evidence',()=>{const ids=d.evidence.filter(e=>e.evidence_type==='WEAKNESS').map(e=>e.evidence_id);JSON.parse(pm.collectionVariables.get('planResult')).weaknesses.forEach(w=>w.evidenceIds.forEach(id=>pm.expect(ids).to.include(id)));});
""")
request('12 Saved plan matches response', 'GET', '/agent/study-plans/{{planId}}', tests="pm.test('Persisted result',()=>pm.expect(pm.response.json().data).to.eql(JSON.parse(pm.collectionVariables.get('planResult'))));")
request('13 Deny B access to A plan', 'GET', '/agent/study-plans/{{planId}}', 404, token='tokenB')
request('14 Same conversation continues', 'POST', '/agent/chat', body={'conversationId':'{{chatConversation}}','message':'只回复已收到，其他数组为空。','knowledgeBaseIds':[]}, tests="pm.test('Same conversation',()=>pm.expect(pm.response.json().data.conversationId).to.eql(pm.collectionVariables.get('chatConversation')));")
request('15 Delete test chat', 'DELETE', '/agent/conversations/{{chatConversation}}')
request('16 Delete test plan conversation', 'DELETE', '/agent/conversations/{{planConversation}}')
request('17 Logout A', 'POST', '/user/logout')
request('18 Logout B', 'POST', '/user/logout', token='tokenB')

variables={'baseUrl':'http://localhost:18200','usernameA':'v87_acceptance_a','usernameB':'v87_acceptance_b','password':'v87-test-only'}
collection={'info':{'name':'V8.7 Agent acceptance (isolated test fixtures)','schema':'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'},'variable':[{'key':k,'value':v} for k,v in variables.items()],'item':items}
out=Path('docs/testing/v87-acceptance.postman_collection.json');out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(collection,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Generated {len(items)} requests: {out}')
