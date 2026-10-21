"""Run a fixed, isolated HTTP RAG evaluation against a running application.

Requires an existing ordinary user JWT in EVAL_TOKEN. Creates a new knowledge
base with synthetic documents; it never mutates an existing base. Results contain
no credentials. See docs/testing/rag-evaluation.md for metric definitions.
"""
import argparse
import json
import os
import pathlib
import time
import urllib.error
import urllib.request
import uuid

CORPUS = [
    ("HashMap", "HashMap is not thread safe; resize may race with writes.", "Does HashMap protect concurrent writes?", "What can happen when HashMap resizes during writes?"),
    ("ConcurrentHashMap", "ConcurrentHashMap supports concurrent access with internal synchronization and does not permit null keys.", "Which map supports concurrent access without external locking?", "Can ConcurrentHashMap use a null key?"),
    ("ArrayList", "ArrayList stores elements in a resizable array and indexed access is constant time.", "Which list uses a resizable array?", "What is the access complexity for an ArrayList index?"),
    ("LinkedList", "LinkedList is a doubly linked list; indexed access traverses nodes.", "Which list uses doubly linked nodes?", "Why is indexed access in LinkedList slow?"),
    ("HashSet", "HashSet stores unique elements using a hash table and does not guarantee iteration order.", "Which collection enforces uniqueness with hashing?", "Does HashSet guarantee iteration order?"),
    ("TreeSet", "TreeSet maintains sorted elements using a tree and comparator ordering.", "Which set keeps elements sorted?", "How does TreeSet determine element order?"),
    ("JavaVolatile", "Java volatile provides visibility and ordering but does not make a compound increment atomic.", "Does volatile make count++ atomic?", "What guarantee does volatile provide across threads?"),
    ("JavaSynchronized", "Java synchronized provides mutual exclusion around a monitor and happens-before on unlock and lock.", "Which keyword provides monitor mutual exclusion?", "What ordering relation does synchronized unlock establish?"),
    ("JavaCompletableFuture", "CompletableFuture composes asynchronous stages with thenCompose and combines independent stages with thenCombine.", "Which CompletableFuture method flattens a dependent stage?", "How can independent CompletableFuture results be combined?"),
    ("JavaStream", "A Java Stream pipeline evaluates intermediate operations lazily until a terminal operation runs.", "When do Stream intermediate operations execute?", "Are Java Stream intermediate operations eager?"),
    ("SpringTransaction", "Spring transaction rollback defaults to unchecked exceptions; checked exceptions need an explicit rollback rule.", "Which exceptions cause default Spring transaction rollback?", "How can a checked exception trigger Spring rollback?"),
    ("SpringRequiresNew", "REQUIRES_NEW suspends the current transaction and begins an independent transaction.", "Which propagation mode suspends the current transaction?", "Does REQUIRES_NEW share the caller transaction?"),
    ("SpringAOP", "Spring AOP applies advice through proxies; a call to another method on the same instance bypasses the proxy.", "Why can Spring AOP advice fail on self invocation?", "What mechanism applies Spring AOP advice?"),
    ("SpringBeanScope", "A Spring singleton bean has one instance per application context, not one instance per JVM.", "What is the scope of a Spring singleton?", "Is a Spring singleton unique across the whole JVM?"),
    ("SpringMVC", "Spring MVC DispatcherServlet selects a handler and delegates through a HandlerAdapter.", "Which servlet dispatches Spring MVC requests?", "How does Spring MVC invoke a selected handler?"),
    ("SpringValidation", "Bean Validation constraints on request bodies run when a Spring controller parameter uses Valid.", "What annotation activates request body Bean Validation?", "How are DTO constraints checked in a Spring controller?"),
    ("MySQLBTree", "A MySQL B-tree index supports equality and ordered range predicates on a leading key prefix.", "Which MySQL index supports ordered range searches?", "Why does a composite index leading prefix matter?"),
    ("MySQLIsolation", "MySQL InnoDB REPEATABLE READ uses a consistent snapshot for ordinary reads and current reads for locking reads.", "What does InnoDB REPEATABLE READ use for ordinary reads?", "Do InnoDB locking reads use the same snapshot as ordinary reads?"),
    ("MySQLExplain", "MySQL EXPLAIN displays the chosen access path and estimated rows for a query plan.", "Which command displays the MySQL query plan?", "Where can estimated rows for a MySQL access path be inspected?"),
    ("MySQLCovering", "A covering index contains all columns required by a query, avoiding a table row lookup.", "What is a covering index?", "How can an index avoid a table row lookup?"),
    ("PostgresMVCC", "PostgreSQL MVCC stores tuple versions and VACUUM removes dead tuples after transactions no longer need them.", "What removes dead PostgreSQL tuple versions?", "Why does PostgreSQL retain multiple tuple versions?"),
    ("PostgresPgvector", "pgvector stores embeddings in a vector column and can index approximate nearest-neighbor search.", "Which PostgreSQL extension stores embedding vectors?", "What does a pgvector approximate index accelerate?"),
    ("RedisAOF", "Redis AOF records write commands; the everysec fsync policy normally syncs about once a second.", "What does Redis AOF record?", "What does the Redis AOF everysec fsync policy mean?"),
    ("RedisRDB", "Redis RDB persistence writes point-in-time snapshots instead of logging every write command.", "Which Redis persistence mode writes snapshots?", "How does Redis RDB differ from AOF?"),
    ("RedisStream", "Redis Streams consumer groups track pending entries until XACK acknowledges processing.", "Which Redis Streams command acknowledges a processed entry?", "What does a consumer group pending list track?"),
    ("RedisCacheAside", "Cache-aside reads cache first and loads from storage on a miss; writes must invalidate or update the cached value.", "What happens on a cache-aside miss?", "Why must cache-aside writes invalidate cached data?"),
    ("RedisTTL", "Redis TTL sets an expiration time for a key and EXPIRE updates that deadline.", "Which Redis command sets a key expiration?", "What does Redis TTL measure?"),
    ("HTTPIdempotency", "HTTP PUT is defined as idempotent; repeating the same request should have the same intended effect.", "Which HTTP update method is idempotent by definition?", "What does HTTP idempotency mean for repeated PUT requests?"),
    ("JWTClaims", "A JWT payload is encoded, not encrypted; a signature protects integrity but not confidentiality.", "Does a JWT signature hide its payload?", "What security property does a signed JWT provide?"),
    ("DockerVolume", "A Docker named volume persists independently of a container lifecycle and can retain database data after restart.", "What retains container database data after restart?", "Does removing a container automatically erase its named volume?"),
]

NEGATIVES = [
    "What feature was introduced in Spring Boot 12.0?",
    "What is the salary of the project's maintainer?",
    "Which company hired this knowledge-base owner yesterday?",
    "What benchmark throughput did this service achieve at 500 users?",
    "What is the private API key of the system administrator?",
]


def call(base_url, method, path, token, payload=None, content_type="application/json"):
    body = None if payload is None else (payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8"))
    headers = {"Authorization": "Bearer " + token}
    if body is not None:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(base_url + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status != 200:
        raise RuntimeError(f"{method} {path}: HTTP {status}")
    result = json.loads(raw)
    if result.get("code") != 200:
        raise RuntimeError(f"{method} {path}: application code {result.get('code')}")
    return result.get("data")


def upload(base_url, token, base_id, name, content):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}.md\"\r\n"
            f"Content-Type: text/markdown\r\n\r\n# {name}\n\n{content}\n\r\n--{boundary}--\r\n").encode("utf-8")
    return call(base_url, "POST", f"/knowledge-bases/{base_id}/documents", token, body,
                "multipart/form-data; boundary=" + boundary)


def wait_ready(base_url, token, document_id, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        document = call(base_url, "GET", f"/knowledge/documents/{document_id}", token)
        if document["status"] == "READY":
            return document
        if document["status"] in ("FAILED", "DELETED"):
            raise RuntimeError(f"Document {document_id} reached {document['status']}")
        time.sleep(2)
    raise TimeoutError(f"Document {document_id} did not become READY")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:18203")
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("rag-evaluation-result.json"))
    parser.add_argument("--agent-sample", type=int, default=5, help="number of positive questions checked with Agent citations")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.agent_sample < 0 or args.agent_sample > len(CORPUS):
        parser.error("--agent-sample must be between 0 and 30")
    if args.dry_run:
        print(json.dumps({"documents": len(CORPUS), "positiveQuestions": len(CORPUS) * 2,
                          "noAnswerQuestions": len(NEGATIVES), "agentPositiveSample": args.agent_sample}))
        return
    token = os.environ.get("EVAL_TOKEN")
    if not token:
        parser.error("EVAL_TOKEN must contain an ordinary test user's JWT")
    base_url = args.url.rstrip("/")
    base = call(base_url, "POST", "/knowledge-bases", token,
                {"name": "RAG evaluation " + uuid.uuid4().hex[:12], "description": "Synthetic isolated quality baseline"})
    base_id = base["id"]
    ids = {}
    for name, fact, _, _ in CORPUS:
        document = upload(base_url, token, base_id, name, fact)
        wait_ready(base_url, token, document["id"])
        ids[name] = document["id"]
        print(f"READY {name}", flush=True)
    positive = []
    for name, _, first, second in CORPUS:
        for question in (first, second):
            hits = call(base_url, "POST", "/knowledge/search", token,
                        {"knowledgeBaseId": base_id, "question": question}) or []
            top_five = [item.get("metadata", {}).get("documentId") for item in hits[:5]]
            top_five = [int(value) if value is not None else None for value in top_five]
            positive.append({"question": question, "expectedDocument": name, "hitAt5": ids[name] in top_five,
                             "topFiveDocumentIds": top_five})
    citations = []
    for name, fact, question, _ in CORPUS[:args.agent_sample]:
        answer = call(base_url, "POST", "/agent/chat", token,
                      {"message": f"仅根据所选知识库回答：{question} 请引用支持答案的资料。", "knowledgeBaseIds": [base_id]})
        cited = answer.get("result", {}).get("citations") or []
        # This is a source-identity check. Semantic support still requires human review.
        citations.append({"question": question, "expectedDocument": name,
                          "sourceCorrect": bool(cited) and all(c.get("documentId") == ids[name] for c in cited),
                          "citedDocumentIds": [c.get("documentId") for c in cited],
                          "answer": answer.get("result", {}).get("message", "")})
    negatives = []
    for question in NEGATIVES:
        answer = call(base_url, "POST", "/agent/chat", token,
                      {"message": "只根据所选资料回答；没有证据请明确说无法确认：" + question,
                       "knowledgeBaseIds": [base_id]})
        cited = answer.get("result", {}).get("citations") or []
        negatives.append({"question": question, "falseCitation": bool(cited),
                          "citedDocumentIds": [c.get("documentId") for c in cited],
                          "answer": answer.get("result", {}).get("message", "")})
    result = {"baseId": base_id, "documentIds": ids, "corpusType": "synthetic",
              "recallAt5": sum(x["hitAt5"] for x in positive) / len(positive),
              "citationSourceAccuracy": (sum(x["sourceCorrect"] for x in citations) / len(citations)) if citations else None,
              "noAnswerFalseCitationRate": sum(x["falseCitation"] for x in negatives) / len(negatives),
              "positive": positive, "citationSample": citations, "noAnswer": negatives}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("baseId", "recallAt5", "citationSourceAccuracy", "noAnswerFalseCitationRate")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
