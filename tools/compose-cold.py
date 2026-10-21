"""Run the isolated Compose cold-start acceptance project without deleting volumes.

Usage: python tools/compose-cold.py start|check|accept|restart|status|stop
Credentials and project identity live only in ignored .stabilization-runtime/cold-compose.json.
The API key is read from the same local sources as run-stabilization-live.py and
is never saved by this script. `stop` retains named volumes.
"""
import importlib.util
import json
import os
import pathlib
import re
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
STATE = ROOT / ".stabilization-runtime" / "cold-compose.json"
CORE_STATE = ROOT / ".stabilization-runtime" / "core-cold-compose.json"
RESULT = ROOT / ".stabilization-runtime" / "cold-demo.json"
RAG_RESULT = ROOT / ".stabilization-runtime" / "cold-rag-evaluation.json"
PROBE_RESULT = ROOT / ".stabilization-runtime" / "cold-rag-probes.json"


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def load_or_create():
    if STATE.exists():
        return json.loads(STATE.read_text(encoding="utf-8"))
    state = {"project": "ai-java-cold-" + secrets.token_hex(5),
             "appPort": unused_port(), "mailPort": unused_port(),
             "withMailpit": STATE != CORE_STATE,
             "postgresPassword": secrets.token_urlsafe(30),
             "s3AccessKey": "cold-" + secrets.token_hex(8),
             "s3SecretKey": secrets.token_urlsafe(30),
             "jwtSecret": secrets.token_urlsafe(48),
             "demoUsername": "cold_" + secrets.token_hex(6),
             "demoPassword": secrets.token_hex(8)}
    STATE.parent.mkdir(exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")
    os.chmod(STATE, 0o600)
    return state


def existing():
    if not STATE.exists():
        raise RuntimeError("No cold-start state. Run 'start' first.")
    state = json.loads(STATE.read_text(encoding="utf-8"))
    if len(state["demoPassword"]) > 20 and not RESULT.exists():
        state["demoPassword"] = secrets.token_hex(8)
        STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")
    return state


def compose(state, *args, capture=False):
    spec = importlib.util.spec_from_file_location("stabilization", ROOT / "tools" / "run-stabilization-live.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    env = module.environment()
    env.update({"DEMO_POSTGRES_PASSWORD": state["postgresPassword"],
                "DEMO_S3_ACCESS_KEY": state["s3AccessKey"],
                "DEMO_S3_SECRET_KEY": state["s3SecretKey"],
                "DEMO_JWT_SECRET": state["jwtSecret"],
                "DEMO_PORT": str(state["appPort"]),
                "DEMO_MAILPIT_PORT": str(state["mailPort"]),
                "COMPOSE_PROGRESS": "quiet"})
    if state.get("withMailpit", True):
        env["QQ_MAIL_AUTH_CODE"] = "unused-isolated-mailpit"
    command = ["docker", "compose", "-p", state["project"], "-f", "compose.yml"]
    if state.get("withMailpit", True):
        command += ["-f", "compose.acceptance.yml"]
    command += list(args)
    result = subprocess.run(command, cwd=ROOT, env=env, check=True,
                            stdout=subprocess.PIPE if capture else None,
                            text=True, encoding="utf-8", errors="replace")
    return result.stdout if capture else None


def sql(state, query):
    return compose(state, "exec", "-T", "postgres", "psql", "-XAtq", "-v", "ON_ERROR_STOP=1",
                   "-U", "postgres", "-d", "interview", "-c", query, capture=True).strip()


def api(state, method, path, token=None, data=None, raw=False):
    body = None if data is None else json.dumps(data).encode("utf-8")
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request("http://127.0.0.1:" + str(state["appPort"]) + path,
                                     data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, payload = error.code, error.read()
    if status != 200:
        raise RuntimeError(f"{method} {path}: HTTP {status}")
    if raw:
        return payload
    value = json.loads(payload)
    if value.get("code") != 200:
        raise RuntimeError(f"{method} {path}: application code {value.get('code')}")
    return value.get("data")


def check(state):
    tables = sql(state, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('app_user','knowledge_document_version','vector_store');")
    extension = sql(state, "SELECT count(*) FROM pg_extension WHERE extname='vector';")
    models = compose(state, "exec", "-T", "ollama", "ollama", "list", capture=True)
    if tables != "3" or extension != "1" or "nomic-embed-text" not in models:
        raise AssertionError(f"database/model initialization incomplete: tables={tables}, vector={extension}, model={'nomic-embed-text' in models}")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:" + str(state["appPort"]) + "/agent/profile", timeout=5) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        except urllib.error.URLError:
            status = None
        if status in (200, 401):
            break
        time.sleep(2)
    else:
        raise TimeoutError("application HTTP health probe did not respond")
    print(json.dumps({"project": state["project"], "appPort": state["appPort"],
                      "mailpitPort": state["mailPort"] if state.get("withMailpit", True) else None,
                      "tables": int(tables),
                      "pgvectorExtension": True, "embeddingModel": "nomic-embed-text",
                      "httpStatus": status}))


def accept(state):
    if RESULT.exists():
        raise RuntimeError("Acceptance result already exists; refusing to create a second demo user")
    state["demoUsername"] = "cold_" + secrets.token_hex(6)
    state["demoPassword"] = secrets.token_hex(8)
    STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")
    env = os.environ.copy()
    env["DEMO_USERNAME"] = state["demoUsername"]
    env["DEMO_PASSWORD"] = state["demoPassword"]
    subprocess.run([sys.executable, str(ROOT / "tools" / "demo-user-flow.py"),
                    "--url", "http://127.0.0.1:" + str(state["appPort"]),
                    "--mailpit-url", "http://127.0.0.1:" + str(state["mailPort"]),
                    "--email", "cold-" + secrets.token_hex(6) + "@example.test", "--output", str(RESULT)],
                   cwd=ROOT, env=env, check=True)


def verify_persistence(state):
    if not RESULT.exists():
        raise RuntimeError("No demo result; run 'accept' before 'restart'")
    result = json.loads(RESULT.read_text(encoding="utf-8"))
    login = api(state, "POST", "/user/login", data={"username": state["demoUsername"],
                                                    "password": state["demoPassword"]})
    token = login["token"]
    document = api(state, "GET", "/knowledge/documents/" + str(result["documentId"]), token)
    source = api(state, "GET", "/knowledge/documents/" + str(result["documentId"]) + "/download", token, raw=True)
    hits = api(state, "POST", "/knowledge/search", token,
               {"knowledgeBaseId": result["knowledgeBaseId"], "question": "When does HashMap resize?"})
    plan = api(state, "GET", "/agent/study-plans/" + str(result["studyPlanId"]), token)
    expected = int(result["documentId"])
    found = any(int(hit.get("metadata", {}).get("documentId", -1)) == expected for hit in hits)
    if document["status"] != "READY" or b"HashMap resize" not in source or not found or not plan:
        raise AssertionError("Post-restart business data, object, vector or plan missing")
    version_preserved = None
    if PROBE_RESULT.exists():
        probe = json.loads(PROBE_RESULT.read_text(encoding="utf-8"))
        version_doc = api(state, "GET", "/knowledge/documents/" + str(probe["documentId"]), token)
        version_hits = api(state, "POST", "/knowledge/search", token,
                           {"knowledgeBaseId": json.loads(RAG_RESULT.read_text(encoding="utf-8"))["baseId"],
                            "question": "HashMap V2 concurrent writes"})
        matching_versions = [int(hit["metadata"]["documentVersion"]) for hit in version_hits
                             if int(hit.get("metadata", {}).get("documentId", -1)) == probe["documentId"]]
        version_preserved = version_doc["activeVersion"] == 2 and bool(matching_versions) and all(v == 2 for v in matching_versions)
        if not version_preserved:
            raise AssertionError("Version two or its vector index did not survive restart")
    print(json.dumps({"postRestart": "PASS", "documentId": expected,
                      "originalFileBytes": len(source), "vectorHit": found,
                      "studyPlanId": result["studyPlanId"], "versionTwoPreserved": version_preserved}))


def evaluate(state):
    login = api(state, "POST", "/user/login", data={"username": state["demoUsername"],
                                                    "password": state["demoPassword"]})
    env = os.environ.copy()
    env["EVAL_TOKEN"] = login["token"]
    subprocess.run([sys.executable, str(ROOT / "tools" / "evaluate-rag.py"),
                    "--url", "http://127.0.0.1:" + str(state["appPort"]),
                    "--output", str(RAG_RESULT)], cwd=ROOT, env=env, check=True)


def http_status(state, method, path, token, data=None):
    body = None if data is None else json.dumps(data).encode("utf-8")
    request = urllib.request.Request("http://127.0.0.1:" + str(state["appPort"]) + path,
                                     data=body, headers={"Authorization": "Bearer " + token,
                                                         "Content-Type": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def probe(state):
    if not RAG_RESULT.exists():
        raise RuntimeError("No RAG baseline; run 'eval' first")
    data = json.loads(RAG_RESULT.read_text(encoding="utf-8"))
    base_id = data["baseId"]
    document_id = data["documentIds"]["HashMap"]
    email = "isolation-" + secrets.token_hex(6) + "@example.test"
    username = "isolate_" + secrets.token_hex(5)
    password = secrets.token_hex(8)
    api(state, "POST", "/user/code?" + urllib.parse.urlencode({"email": email}))
    deadline = time.monotonic() + 60
    code = None
    while time.monotonic() < deadline:
        url = "http://127.0.0.1:" + str(state["mailPort"]) + "/view/latest.txt?" + urllib.parse.urlencode({"query": "to:" + email})
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                match = re.search(r"(?<!\d)\d{6}(?!\d)", response.read().decode("utf-8"))
                if match:
                    code = match.group()
                    break
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        time.sleep(1)
    if not code:
        raise TimeoutError("isolation registration email missing")
    api(state, "POST", "/user/register", data={"username": username, "password": password,
                                               "email": email, "code": code})
    other = api(state, "POST", "/user/login", data={"username": username, "password": password})["token"]
    isolation = {"baseGet": http_status(state, "GET", f"/knowledge-bases/{base_id}", other),
                 "documentGet": http_status(state, "GET", f"/knowledge/documents/{document_id}", other),
                 "search": http_status(state, "POST", "/knowledge/search", other,
                                       {"knowledgeBaseId": base_id, "question": "HashMap"})}
    if any(value != 404 for value in isolation.values()):
        raise AssertionError(f"Cross-user isolation failed: {isolation}")
    owner = api(state, "POST", "/user/login", data={"username": state["demoUsername"],
                                                    "password": state["demoPassword"]})["token"]
    new_fact = "HashMap is not safe for concurrent writes. Version two marker: " + secrets.token_hex(6) + "."
    boundary = secrets.token_hex(12)
    payload = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"HashMap-v2.md\"\r\n"
               f"Content-Type: text/markdown\r\n\r\n# HashMap V2\n\n{new_fact}\n\r\n--{boundary}--\r\n").encode("utf-8")
    request = urllib.request.Request("http://127.0.0.1:" + str(state["appPort"]) + f"/knowledge/documents/{document_id}/file",
                                     data=payload, headers={"Authorization": "Bearer " + owner,
                                                            "Content-Type": "multipart/form-data; boundary=" + boundary}, method="PUT")
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 200 or json.loads(response.read()).get("code") != 200:
            raise AssertionError("version two upload failed")
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        document = api(state, "GET", f"/knowledge/documents/{document_id}", owner)
        if document["status"] == "READY" and document["activeVersion"] == 2:
            break
        if document["status"] == "FAILED":
            raise AssertionError("version two indexing failed")
        time.sleep(2)
    else:
        raise TimeoutError("version two was not published")
    hits = api(state, "POST", "/knowledge/search", owner,
               {"knowledgeBaseId": base_id, "question": new_fact})
    matching = [hit for hit in hits if int(hit.get("metadata", {}).get("documentId", -1)) == document_id]
    versions = [int(hit["metadata"]["documentVersion"]) for hit in matching]
    if not matching or any(version != 2 for version in versions):
        raise AssertionError(f"Old or missing HashMap version in results: {versions}")
    report = {"crossUserHttpStatuses": isolation, "documentId": document_id,
              "activeVersion": 2, "matchedHitVersions": versions,
              "oldVersionVisible": False}
    PROBE_RESULT.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))


def main():
    global STATE
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    action = sys.argv[1]
    if action.startswith("core-"):
        STATE = CORE_STATE
        action = action[5:]
        if action not in {"start", "check", "restart", "status", "stop"}:
            raise SystemExit(__doc__)
    elif action not in {"start", "check", "accept", "restart", "eval", "probe", "status", "stop"}:
        raise SystemExit(__doc__)
    state = load_or_create() if action == "start" else existing()
    if action == "start":
        print(json.dumps({"project": state["project"], "appPort": state["appPort"],
                          "mailpitPort": state["mailPort"] if state.get("withMailpit", True) else None}), flush=True)
        compose(state, "config", "--quiet")
        compose(state, "up", "-d", "--build", "--wait", "--wait-timeout", "900")
        check(state)
    elif action == "check":
        check(state)
    elif action == "accept":
        accept(state)
    elif action == "restart":
        compose(state, "restart")
        compose(state, "up", "-d", "--wait", "--wait-timeout", "600")
        check(state)
        if state.get("withMailpit", True):
            verify_persistence(state)
    elif action == "eval":
        evaluate(state)
    elif action == "probe":
        probe(state)
    elif action == "status":
        print(compose(state, "ps", capture=True))
    elif action == "stop":
        compose(state, "down")  # Keep the named volumes and state for review.


if __name__ == "__main__":
    main()
