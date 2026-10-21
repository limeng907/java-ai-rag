"""Exercise the real registration-to-study-plan HTTP flow with a test mailbox.

The mail verification code is read interactively, or from DEMO_MAIL_CODE for an
external test-mail adapter. Only synthetic data is uploaded. No JWT, password,
email address or verification code is written to the result file.
"""
import argparse
import json
import os
import pathlib
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def request(url, method, path, token=None, data=None, raw=None, content_type="application/json"):
    body = raw if raw is not None else (None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8"))
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = content_type
    try:
        with urllib.request.urlopen(urllib.request.Request(url + path, data=body, headers=headers, method=method), timeout=120) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, payload = error.code, error.read()
    if status != 200:
        raise RuntimeError(f"{method} {path}: HTTP {status}")
    parsed = json.loads(payload)
    if parsed.get("code") != 200:
        raise RuntimeError(f"{method} {path}: application code {parsed.get('code')}")
    return parsed.get("data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:18203")
    parser.add_argument("--email", required=True, help="test mailbox that can receive the verification code")
    parser.add_argument("--mailpit-url", help="isolated Mailpit URL; reads the captured code without real email")
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("demo-user-flow-result.json"))
    args = parser.parse_args()
    url = args.url.rstrip("/")
    username = os.environ.get("DEMO_USERNAME") or "demo_" + uuid.uuid4().hex[:12]
    password = os.environ.get("DEMO_PASSWORD") or secrets.token_hex(8)
    steps = []
    request(url, "POST", "/user/code?" + urllib.parse.urlencode({"email": args.email}))
    steps.append("verification-email-sent")
    code = os.environ.get("DEMO_MAIL_CODE")
    if not code and args.mailpit_url:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(args.mailpit_url.rstrip("/") + "/view/latest.txt?" +
                                            urllib.parse.urlencode({"query": "to:" + args.email}), timeout=5) as response:
                    message = response.read().decode("utf-8")
                import re
                match = re.search(r"(?<!\d)\d{6}(?!\d)", message)
                if match:
                    code = match.group()
                    break
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
            time.sleep(1)
        if not code:
            raise TimeoutError("verification email was not captured by Mailpit")
    code = code or input("Enter the verification code from the test mailbox: ").strip()
    if not code:
        raise ValueError("verification code is required")
    request(url, "POST", "/user/register", data={"username": username, "password": password,
                                                   "email": args.email, "code": code})
    steps.append("registered-with-email-code")
    login = request(url, "POST", "/user/login", data={"username": username, "password": password})
    token = login["token"]
    steps.append("logged-in-with-jwt")
    request(url, "PUT", "/agent/profile", token,
            {"target": "Java backend internship", "level": "beginner", "dailyMinutes": 45})
    base = request(url, "POST", "/knowledge-bases", token,
                   {"name": "Demo " + uuid.uuid4().hex[:8], "description": "Synthetic interview example"})
    base_id = base["id"]
    marker = "DEMO_HASHMAP_RESIZE_" + uuid.uuid4().hex[:8]
    content = f"# HashMap resize\n\nHashMap doubles its table capacity after its load factor threshold is exceeded. Evidence marker: {marker}.\n"
    boundary = uuid.uuid4().hex
    multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"DemoHashMap.md\"\r\n"
                 f"Content-Type: text/markdown\r\n\r\n{content}\r\n--{boundary}--\r\n").encode("utf-8")
    document = request(url, "POST", f"/knowledge-bases/{base_id}/documents", token, raw=multipart,
                       content_type="multipart/form-data; boundary=" + boundary)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        document = request(url, "GET", f"/knowledge/documents/{document['id']}", token)
        if document["status"] == "READY":
            break
        if document["status"] == "FAILED":
            raise RuntimeError("document indexing failed")
        time.sleep(2)
    else:
        raise TimeoutError("document did not become READY")
    steps.append("uploaded-and-indexed-document")
    interview = request(url, "POST", "/interview", token,
                        {"category": "Java", "difficulty": "EASY", "knowledgeBaseIds": [base_id]})
    session_id = interview["sessionId"]
    request(url, "POST", f"/interview/{session_id}/answer", token,
            {"answer": "HashMap is not thread safe. Its capacity may double after the load factor threshold is exceeded."})
    evaluation = request(url, "POST", f"/interview/{session_id}/finish", token)
    if evaluation.get("score") is None:
        raise AssertionError("interview evaluation was not saved")
    steps.append("interview-evaluated")
    plan = request(url, "POST", "/agent/study-plans", token,
                   {"message": "请根据刚才的面试评价和知识库资料生成个性化学习计划，并引用资料。", "knowledgeBaseIds": [base_id]})
    if not plan.get("studyPlanId"):
        raise AssertionError("study plan was not saved")
    steps.append("study-plan-saved")
    continued = request(url, "POST", "/agent/study-plans", token,
                        {"conversationId": plan["conversationId"], "message": "请简要说明第一天应该学什么。",
                         "knowledgeBaseIds": [base_id]})
    if continued.get("conversationId") != plan["conversationId"]:
        raise AssertionError("conversation continuity failed")
    steps.append("conversation-continued")
    result = {"steps": steps, "knowledgeBaseId": base_id, "documentId": document["id"],
              "interviewSessionId": session_id, "studyPlanId": plan["studyPlanId"],
              "conversationId": plan["conversationId"], "evaluationScore": evaluation["score"]}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
