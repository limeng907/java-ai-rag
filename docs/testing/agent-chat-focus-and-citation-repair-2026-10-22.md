# Agent 问题对齐与知识引用修复记录

日期：2026-10-22。范围：`/agent/chat` 的普通知识问答，以及 `CHAT` 回答中的 Java 类名引用校验。

## 复现与原因

2026-10-21 的隔离 RAG 评测中，ArrayList 提问引用了正确文档，但返回正文变成个人薄弱点说明；LinkedList 回答补充了 ArrayList 的性质，却只引用 LinkedList Chunk。原始样本见 [RAG 评测记录](v8-cold-acceptance-evidence/rag-evaluation.json)。

1. `CHAT` 与 `STUDY` 原来共用全部只读工具。普通问题也能调用 `analyzeWeaknesses`；只要模型输出非空 `weaknesses`，`AgentEvidenceValidator.complete` 就会用固定诊断文字替换整个 `message`，从而吞掉知识回答。
2. 原有 Grounding 只校验 `citations` 指向本次真实 KNOWLEDGE 记录，以及来源、版本和 Chunk 身份；没有检查正文中每个明确提到的技术对象是否与对应引用出现在同一句中。

## 修复

- 按本轮请求授权工具：普通 `CHAT` 仅可读取学习档案、搜索知识和题库；用户明确询问自己的面试历史或薄弱点时，才开放历史与诊断工具。`STUDY` 继续负责七天计划和个人诊断。即使活动 Prompt 存在数据库中，运行时也追加当前模式的职责约束。
- 普通 `CHAT` 的 `weaknesses` 和所有 `CHAT` 的 `plan` 必须为空。当前问题中出现的 Java 类名必须在正文中得到回应；引用了包含类名的 Chunk 时，正文也要指出该对象，避免返回与提问无关的套话。
- 对正文中 `ArrayList`、`LinkedList`、`HashMap` 等 CamelCase 类名逐句检查：每句必须出现 `[K1]`、`（K1）` 等本次引用标记，且所指 KNOWLEDGE Chunk 的正文必须包含该类名。多个类出现在同一句时，每个类都要有匹配证据。缺证且模型调用预算允许时，给模型一次搜索修复机会；查无依据时删去陈述，仍不合格则拒绝提交。
- 更新 `CHAT` 和 `STUDY` 的资源 Prompt，明确知识回答与学习诊断的分工。对已有数据库活动 Prompt，运行时追加规则即可生效，无需改写用户自定义模板。

## 测试设计

| 场景 | 预期 |
| --- | --- |
| 普通 ArrayList 问题调用 `analyzeWeaknesses` | 工具调用在执行前被拒绝，诊断数据不被读取。 |
| 普通知识回答返回 `weaknesses` 或计划 | 输出校验拒绝。 |
| LinkedList 句子引用 K1，ArrayList 对比也只引用 K1 | 拒绝对比；K1 不含 ArrayList。 |
| ArrayList 对比补充包含该类的 K2，并在同句标记 | 接受；两个来源均在引用列表中。 |
| 模型先输出无证据的 ArrayList 对比，随后删去对比 | 第一次输出被拒绝，第二次合规回答才会返回。 |
| 模型首答提到 ArrayList 但没有检索 | 在预算内调用 `searchKnowledge`，补上对应 Chunk 引用后返回。 |
| 同一修复流程写入 PostgreSQL | 只提交修复后的正文、一次有效引用和两条会话消息。 |
| 没有资料，明确回答“无法确认”且无引用 | 允许保守回答。 |

测试文件：`ChatAnswerPolicyTest`、`ToolPolicyTest`、`AgentRuntimeTest`、`V88GroundingPostgresTest`。运行命令（本机 Windows 的 Mockito 测试 JVM 需显式加载 agent）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -q '-DargLine=-javaagent:C:\Users\45152\.m2\repository\org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar' verify
```

最终默认 `verify` 通过：Surefire 报告列出 223 个用例，实际执行 101 个、按环境条件跳过 122 个，失败 0、错误 0。普通问答正文拦截、缺证据后补检索和删去无依据对比的定向测试也分别通过。

数据库集成测试使用本机 PostgreSQL 的 `postgres` 数据库，并由测试创建、删除随机隔离 schema；没有修改既有业务表。运行时额外设置：

```powershell
$env:V8_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:5432/postgres'
mvn -q '-DargLine=-javaagent:C:\Users\45152\.m2\repository\org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar' '-Dtest=V88GroundingPostgresTest' test
```

`V88GroundingPostgresTest` 原有 21 个用例中，首轮 20 个通过；`t837ChangedLocalPromptDoesNotOverrideActiveDatabaseVersion` 在测试过程约 29 分钟停顿后遇到执行租约过期，单独重跑该用例通过。新增的 `t839UncitedArrayListComparisonIsRemovedBeforeDatabaseCommit` 单独运行通过。随后补跑完整 `V88GroundingPostgresTest`，Surefire 报告为 **22 个执行、0 失败、0 错误、0 跳过**，耗时 70.133 秒。

## Docker 真实模型 HTTP 回放

Docker Engine 恢复后，2026-10-22 使用 `compose-cold.py start` 重新构建并启动当前代码的隔离 Compose 项目。应用、PostgreSQL、Redis、RustFS、Ollama 均通过容器健康检查。普通测试用户在原隔离 RAG 评测的 30 篇合成文档知识库中，以原始两条问题调用 `/agent/chat`。回放脚本确认文档状态为 `READY`，逐句核对类名和对应文档的行内 `K` 引用，同时检查 `weaknesses` 与 `plan` 为空。

```powershell
py -3 tools/compose-cold.py start
py -3 tools/compose-cold.py replay-chat
```

以上命令复用本机已有的隔离验收用户和 RAG 基线。全新环境需先运行 `py -3 tools/compose-cold.py accept` 和 `py -3 tools/compose-cold.py eval`，再执行 `replay-chat`。

完整脱敏结果见 [真实模型回放 JSON](agent-chat-focus-evidence/live-replay.json)。两问均通过：

| 原始问题 | 实际结果 |
| --- | --- |
| Which list uses a resizable array? | 直接回答 `ArrayList`，两句 ArrayList 技术陈述分别引用 `ArrayList.md` 的 `K6`；额外提及 LinkedList 时引用其 `LinkedList.md` Chunk `K7`。`weaknesses=[]`、`plan=[]`。 |
| Which list uses doubly linked nodes? | 直接回答 `LinkedList`，对应 `LinkedList.md` 的 `K1`；ArrayList 对比陈述对应 `ArrayList.md` 的 `K5`。`weaknesses=[]`、`plan=[]`。 |

两次请求各自创建独立执行；`K` 编号只在本次执行内有效。人工对照原文后，这两条技术解释与合成文档一致。这证明原始可复现样本在当前代码和该测试环境中已修复；它不代表所有提问或所有模型输出都能得到完整语义引用。

## 边界

这一校验针对可明确识别的 Java CamelCase 类名和引用遗漏，不能证明所有自然语言结论的语义正确性。若多个类的事实都写在同一 Chunk 中，程序只能确认它们共享该 Chunk，仍需人工评估具体陈述是否被原文支持。缩写、中文概念和没有类名的技术句子仍依赖 Prompt、原有证据校验与人工评测。
