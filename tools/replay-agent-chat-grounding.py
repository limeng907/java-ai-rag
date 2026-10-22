"""Replay the two V8 Agent regressions against a running app and test KB.

Set EVAL_TOKEN to an ordinary test user's JWT. The baseline file is the output
of evaluate-rag.py and identifies that user's isolated synthetic documents.
"""
import argparse
import importlib.util
import json
import os
import pathlib
import re
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("evaluate_rag", ROOT / "tools" / "evaluate-rag.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)

QUESTIONS = [
    ("ArrayList", "Which list uses a resizable array?"),
    ("LinkedList", "Which list uses doubly linked nodes?"),
]
SUBJECT = re.compile(r"\b(?:[A-Z][a-z0-9]+){2,}\b")
MARKER = re.compile(r"[\[（(](K[0-9]+)[\]）)]")
SENTENCE = re.compile(r"(?<=[。！？!?])|(?<=\.)\s+|\n+")


def check_answer(answer, subject, ids):
    result = answer["result"]
    message = result["message"]
    citations = result.get("citations") or []
    by_evidence = {item["evidenceId"]: item for item in citations}
    issues = []
    if subject not in message:
        issues.append("answer does not name " + subject)
    if result.get("weaknesses"):
        issues.append("ordinary CHAT produced weaknesses")
    if result.get("plan"):
        issues.append("CHAT produced a study plan")
    if any(term in message for term in ("薄弱点", "个人薄弱", "学习诊断")):
        issues.append("answer drifted into personal diagnosis")
    for sentence in SENTENCE.split(message):
        markers = set(MARKER.findall(sentence))
        for name in set(SUBJECT.findall(sentence)) & set(ids):
            if not any(by_evidence.get(marker, {}).get("documentId") == ids[name]
                       for marker in markers):
                issues.append(f"{name} lacks its own cited Chunk in: {sentence[:120]}")
    if not any(item.get("documentId") == ids[subject] for item in citations):
        issues.append("expected source missing from citations")
    return issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--baseline", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    token = os.environ.get("EVAL_TOKEN")
    if not token:
        parser.error("EVAL_TOKEN must contain an ordinary test user's JWT")
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    ids = {name: baseline["documentIds"][name] for name, _ in QUESTIONS}
    base_id = baseline["baseId"]
    url = args.url.rstrip("/")
    for name, doc_id in ids.items():
        doc = evaluation.call(url, "GET", f"/knowledge/documents/{doc_id}", token)
        if doc["status"] != "READY":
            raise RuntimeError(f"{name} document is {doc['status']}")
    samples = []
    for name, question in QUESTIONS:
        started = time.monotonic()
        answer = evaluation.call(url, "POST", "/agent/chat", token, {
            "message": f"仅根据所选知识库回答：{question} 请引用支持答案的资料。",
            "knowledgeBaseIds": [base_id],
        })
        issues = check_answer(answer, name, ids)
        result = answer["result"]
        samples.append({"question": question, "expectedDocument": name,
                        "answer": result["message"], "citations": result.get("citations") or [],
                        "weaknesses": result.get("weaknesses"), "plan": result.get("plan"),
                        "durationSeconds": round(time.monotonic() - started, 2),
                        "issues": issues, "passed": not issues})
        print(json.dumps({"question": question, "passed": not issues,
                          "issues": issues}, ensure_ascii=False), flush=True)
    report = {"baseId": base_id, "documentIds": ids, "samples": samples,
              "passed": all(sample["passed"] for sample in samples)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
