# V8 Compose 空卷、业务闭环与 RAG 扩大评测

日期：2026-10-21（Asia/Tokyo）。本轮使用 Docker Engine 29.2.1 和本工作区当前源码。所有写入都发生在两个新建的隔离 Compose 项目内，未清理或改写原开发卷。测试对象、密码与本地 API Key 没有进入 Git；随机测试凭证保存在被忽略的 `.stabilization-runtime/`。本报告的通过状态仅对应实际执行过的步骤。

## 1. Compose 空卷冷启动

| 项目 | 启动方式 | 实际结果 |
| --- | --- | --- |
| `ai-java-cold-300b48d72c` | 原版 `compose.yml`，独立项目名、空数据卷，`up -d --build --wait` | PostgreSQL、Redis、RustFS、Ollama、Embedding 初始化、应用依赖顺序完成；应用和持续运行的依赖服务健康。PostgreSQL 中 `app_user`、`knowledge_document_version`、`vector_store` 均存在，`vector` 扩展存在，Ollama 列出 `nomic-embed-text`。无凭证访问 `/agent/profile` 返回预期的 401。重启后上述检查再次通过。 |
| `ai-java-cold-340b84f683` | `compose.yml` 加 `compose.acceptance.yml`，独立项目名、空数据卷 | 同样通过初始化与健康检查；额外使用 [Mailpit](https://mailpit.axllent.org/docs/install/docker/) 捕获测试验证码，避免向真实邮箱发送测试邮件。 |

首次下载 Ollama 镜像与 Embedding 模型明显慢于后续启动；完整健康检查最终通过。原版 Compose 的空卷启动已实测，业务闭环则使用上述本地邮件覆盖文件进行。

验收结束后对这两个隔离项目执行了不带 `-v` 的 `down`；六个测试命名卷保留，原开发环境的 PostgreSQL、Redis 和 RustFS 容器仍在运行。

## 2. 普通用户业务闭环与重启

`tools/compose-cold.py accept` 调用真实 HTTP 接口执行了 7 个连续阶段：邮件验证码发送与读取、验证码注册、JWT 登录、Markdown 上传并等到 READY、AI 模拟面试与评价保存、个性化学习计划保存、同一 `STUDY` 会话续聊。结果见 [业务记录](v8-cold-acceptance-evidence/business-flow.json)。本次合成面试评价为 15 分；这个数字只是测试数据，不是模型评分准确性的证明。

执行 `restart` 后重新登录并读取同一用户的文档、下载 RustFS 原文、检索 pgvector Chunk、读取已保存学习计划，全部通过；再次重启后 V2 文档仍为活跃版本且只检索到 V2 Chunk。原始文件下载为 145 字节，向量检索和学习计划 ID 与重启前一致。

## 3. 固定 RAG 质量基线

评测新建 30 篇合成后端知识文档（集合、并发、Spring、MySQL、PostgreSQL、Redis、HTTP、JWT、Docker），每篇两个有答案问题，共 60 个正例，另有 5 个资料外问题。逐篇真实上传并等 Worker 发布，再调用真实向量检索与 Agent 接口。逐题结果见 [评测数据](v8-cold-acceptance-evidence/rag-evaluation.json)，指标定义见 [评测说明](rag-evaluation.md)。

| 指标 | 本次结果 | 解释 |
| --- | --- | --- |
| 文档级 Recall@5 | 60/60 | 预期文档 ID 在前 5 个 Chunk 中出现；不是语义答案准确率。 |
| Top-1 文档命中 | 59/60 | 唯一未命中首位的问题是“Redis RDB 与 AOF 有何区别”；AOF 文档排第一，RDB 文档仍在前 5。 |
| 预期来源出现率 | 5/5 | 抽样 Agent 回答的引用均包含预期文档。 |
| 仅引预期来源率 | 3/5 | HashMap 与 ConcurrentHashMap 两题还引用了合理的对比文档；该数不能直接视为引用错误率。 |
| 无答案误引率 | 0/5 | 五个资料外问题没有返回知识库引用，回答均明确表示无法确认。 |

人工阅读这 5 个引用样本后，**3/5 的完整回答**同时满足回答相关、引用覆盖所述技术事实：HashMap、ConcurrentHashMap、HashSet。ArrayList 样本虽引用了 ArrayList 文档，却输出与提问无关的个人薄弱点说明；LinkedList 样本正确解释链表，但额外陈述 ArrayList 的性质时没有给该对比事实单独引用。该小样本人工判断属于本次质量发现，不能外推到所有回答。

## 4. 隔离和版本探针

另一名用户经本地验证码正常注册后，访问评测知识库、其中的文档及该知识库检索接口分别返回 HTTP 404。随后把 HashMap 文档替换为 V2，等到 READY 后，命中该文档的检索结果只带 `documentVersion=2`；再次重启仍保持 V2。记录见 [隔离与版本结果](v8-cold-acceptance-evidence/isolation-version.json)。

## 5. 基础执行观察与边界

隔离验收数据库中已完成 Agent 执行的只读聚合：`CHAT` 10 次，P95 8.300 秒、模型调用合计 22 次、工具调用合计 25 次；`STUDY` 3 次，P95 18.344 秒、模型调用合计 13 次、工具调用合计 23 次。该 P95 来自少量串行合成请求，并非 10/30/50 并发性能结论。`INTERVIEW` 4 次、`EVALUATION` 2 次；这些调用包含脚本修正前的部分成功请求，因此不能当作一次完整用户流程的成本账单。未采集供应商 Token 用量或实际费用。

本轮语料是短篇合成文档，且 Agent 只人工核对了 5 个正例；还需要真实复杂语料、更多无答案与冲突问题、并发压测和长期故障观察。注册验收使用本地邮件接收器；它验证了发送、验证码读取与注册接口，不验证真实 QQ SMTP 的可达性。GitHub 远端仓库尚未连接，本地 Git 提交不等于已发布。
