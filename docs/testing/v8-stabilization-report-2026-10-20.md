# V8 稳定化与工程验收报告

日期：2026-10-20（Asia/Tokyo）。对象：当前 `D:\Ai-Java-Interview` 工作区，而不是历史版本的独立检出。源代码改动和构建证据见 [证据清单](v8-stabilization-evidence/manifest.json)及[审阅用补丁](v8-stabilization-evidence/source-changes.patch)。本目录的测试数据是独立容器中的合成数据；原开发数据库和 8200 服务未用于本轮写入。

## 结论及其范围

本轮承接 [V8.7 AgentRuntime / Tool Calling](v87-test-report-2026-10-19.md)、[V8.8 Grounding](v88-test-report-2026-10-19.md)、[V8.9 AgentMemory](v89-test-report-2026-10-19.md) 三份测试报告：先把构建与真实 RAG 链路跑通，再收紧事实输出和精确记忆，随后处理摘要漂移、重复工具成本与 Docker 验收。历史报告中的测试次数属于各自执行范围，不能相加当作本轮总数。

本轮完成了标准构建、真实文件到 Agent 引用的 RAG 链路、三个故障与版本切换场景、已保存面试事实的确定性输出、两类有来源的精确会话回忆、只读工具重复调用复用、数字学习时间的增量摘要保护，并运行了 Docker 应用的真实 HTTP/模型验收。报告只把执行过的测试列为通过。真实模型抽样、固定检索集和确定性代码检查分开计数。

这些结果**没有**证明任意自由文本都不会编造、任意问题都能被检索或摘要、模型评分本身客观正确，也没有证明高并发性能、持续运行稳定性、所有 Prompt Injection 变体或 Compose 全部服务从空卷冷启动。上述项目仍是后续上线门槛，不能随 V8.10 一起被默认视为已解决。

## 代码与行为变更

| 范围 | 改动及完成条件 |
| --- | --- |
| Grounding | `AgentEvidenceValidator.complete` 以已核对的 Evidence 生成薄弱点原因；存在个人薄弱点时，个人评价总述由后端生成。45 分结构化事实与模型写成 90 分的原因/总述，最终保存及返回都不再包含 90 分。原 AI 评分仍标注为历史记录，未被当作客观能力结论。计划任务等开放建议仍是模型文本，未获得通用语义校验。 |
| 精确记忆 | `memoryField` 支持 `DAILY_MINUTES`、`INTERVIEW_COMPANY`，两个常见问题也自动进入确定性回忆路径；只查当前用户、当前会话已保存的原始 USER 消息。返回 `FOUND`、`NOT_FOUND` 或 `CONFLICT`、实际消息 ID 和原文；读取超过 1000 条时明确返回范围限制。模型不参与这类回忆。其他聊天问题沿用原模型路径。 |
| 简单事实确认 | 明确、短小的“我每天只有 45 分钟”等受支持陈述由后端确认并在事务中保存，再由后续回忆找到真实消息 ID。这个收窄的路径不调用模型或工具。含假设、区间和不确定措辞的句子不走该路径。 |
| 摘要 | 摘要前缀中的明确每日学习时间/面试公司陈述通过原始 USER 消息复核，旧预算被显式更正后从 `userStatements` 和 `preferences` 移除；相关目标与偏好其余语句保留。模型仍概括其他细节，因此这不是通用摘要正确性保证。 |
| 工具成本 | 单次执行内对参数规范化后相同的 `searchKnowledge`/`searchQuestions` 结果复用；仍记每个逻辑调用、调用 ID 与证据，且提交前仍检查版本。缓存不跨执行，异常结果不复用。模型/工具次数与搜索命中数写入执行日志。超时压力回归发现并修复了缓存锁跨外部 I/O 的问题。 |
| 部署 | 新增 Dockerfile、Compose 定义和健康检查。标准输出目录的 Spring Boot JAR 在清理后构建成功；Docker 应用镜像独立启动且健康，并通过真实 HTTP 验收。Compose 配置通过语法检查；完整托管 Ollama/空卷 Compose 冷启动仍未实测。 |

## 测试矩阵

| 层次 | 执行结果 | 能证明什么 |
| --- | --- | --- |
| 标准构建基线 | `mvn clean verify` 成功，67 通过、108 因未启用隔离服务变量跳过 | 默认目录 JAR 可打包；不能把跳过计为通过。日志：[基线](v8-stabilization-evidence/clean-baseline-build.log)。 |
| 最终 JUnit 去重 | 215 个唯一用例，215 通过、0 失败、0 跳过 | 完整构建中原有两项实时冒烟因未设置 URL 被跳过；它们在独立 Docker 应用上补跑通过，最终按唯一方法去重计数。日志：[最终完整构建](v8-stabilization-evidence/verified-build-final.log)、[原有实时冒烟](v8-stabilization-evidence/existing-live-smokes.log)。 |
| 文件生命周期 | 真正上传 Markdown 到 RustFS，Redis Stream 消费、TokenTextSplitter、Ollama `nomic-embed-text` 768 维、pgvector 召回，Agent 引用提交 | 真实来源/版本/Chunk 可追踪。重复任务不重复 Chunk；部分向量写入失败后 V1 保持可用，重试后仅 V2 有效；陈旧租约可恢复；删除及跨用户检索失败。证据：[RAG 观察](v8-stabilization-evidence/rag-observations.json)。 |
| 检索质量样本 | 3 个固定文档、6 个预先定义的问题，Top-1 命中 6/6 | 只证明这个小型固定集，不能推断生产语料整体召回率。 |
| Docker/真实模型 | 14 个 HTTP/模型验收点全通过，其中 3 组会话各做 3 次摘要，9 个摘要版本保留计划目标和学习偏好；45→30 分钟更正生效 | 验证本次合成路径及一次资料不足回答，不是长期概率保证。证据：[实时结果](v8-stabilization-evidence/live-results.json)、[最终执行日志](v8-stabilization-evidence/live-final-run.log)。 |

### 关键异常与边界证据

1. **索引部分写入**：故意在向量写入首块后抛异常，失败 V2 不成为引用；重试后实际向量数等于发布的 Chunk 数。
2. **并发版本切换**：同一执行两次搜索只做一次真实检索，但 V2 在生成后发布时，最终旧 K1 引用仍被提交前复核拒绝，零消息落库。
3. **未知事实**：当前会话原始 USER 消息没有公司名称时，结构化结果为 `NOT_FOUND`、无 `value`，执行审计模型调用数为 0；冲突和超过读取范围分别返回相应状态/标记。
4. **自由文本冲突**：模拟模型输出 `score=45`、`reason` 和 `message` 写 90，最终 JSON 与持久化 JSON 均没有 90。对开放计划任务的任意语义矛盾仍需另行约束或审核。
5. **超时与复用**：已有“模型/工具忽略中断”回归与新增复用测试确认调用计数可读取且迟到结果不能成功提交；这不证明外部供应商一定停止计费或底层请求已经物理终止。
6. **摘要更正回归**：首次真实模型复测发现，模型把旧“45 分钟”改写后仍留在摘要偏好中，导致新“30 分钟”并存；[失败样本](v8-stabilization-evidence/live-failed-before-guard.json)已归档。随后按原始 USER 消息校验数值时间陈述，增加相关单测，最新镜像的 9 次摘要验收全部通过。这一保护只覆盖本报告列出的精确事实类型。

## 成本与性能观察

优化针对两个已观察到的浪费。相同搜索参数的两次逻辑工具调用在测试中只进行一次后端检索，仍保留两条调用记录。真实 HTTP 抽样中，优化前一句明确的学习时间陈述曾触发 4 次模型、10 次工具；现在受支持的明确陈述由后端确认，定向数据库测试中是 0 次模型、0 次工具，最终 Docker HTTP 复测结果见实时记录。未采集供应商账单 Token 数，不能据此声称整体 Token 节省比例；也没有开展吞吐量或 P95 压测。

## 构建、部署与复现

在 Java 21、Maven 3.9.11、独立 pgvector PostgreSQL 17、Redis 8、RustFS、已有本机 Ollama `nomic-embed-text` 和配置的 DeepSeek 下，最终使用 `mvn -o -s tools/maven-central-settings.xml -gs tools/maven-central-settings.xml clean verify`。`-o` 仅选择预缓存依赖，仍执行完整 Maven 生命周期。构建产物为 `target/Ai-Java-Interview-1.0-SNAPSHOT.jar`。执行环境变量与隔离服务见 [运行脚本](../../tools/run-stabilization-live.py)；脚本不把 API 密钥或 JWT 写进证据目录。

镜像使用该 JAR 构建。应用以 `127.0.0.1:18203` 的独立端口接入隔离测试容器，Docker 健康状态为 `healthy`；真实 HTTP 验收覆盖合成用户登录、上传、索引、Agent 检索/引用、会话记忆、删除。测试用户由隔离数据库直接创建，**未验收注册和邮件验证流程**。Docker Compose 文件还声明 PostgreSQL、Redis、RustFS、Ollama、Embedding 初始化和应用依赖；本轮已运行 `docker compose config --quiet`，但没有运行整套 Compose 从空卷拉取模型后启动，不能把配置检查写成冷启动验收。

需要自行启动完整 Compose 时，先以项目根目录的 `.env.example` 建立本地 `.env`，填入真实 DeepSeek API Key 和随机测试密码，然后在 `mvn clean verify` 成功后运行 `docker compose up -d --build --wait`。此命令属于已提供的部署路径，不在本轮“已实测通过”结论内。

最后请先阅读 [证据清单](v8-stabilization-evidence/manifest.json) 中的唯一测试数、源码 SHA-256 和测试明细，再按需复现。`source-before.zip` 是本轮编辑前的源码/SQL/POM 快照；补丁只比较 `src` 变更。当前目录不是 Git 仓库，因此没有 Git 提交或可展示的 `git diff`。

## 尚需继续处理

- 如需把“任意自然语言都不编造”作为硬性要求，需要收紧计划任务等开放文本的展示契约，或增加可靠的语义事实校验；本轮确定性保障仅覆盖所列个人事实与精确回忆类型。
- 在更大、更杂的知识集上评估检索相关性、无答案误引率和跨用户负样本；固定 6 题只能作回归探针。
- 持续采集真实模型 Token/费用、并发下的 P95 和超时后底层请求/供应商计费情况。本轮只有执行次数与耗时样本。
- 用全新空卷执行一次完整 Compose 的 Ollama 拉模、迁移、健康检查和业务演示；本轮证实的是 Docker 应用与隔离依赖的运行链路。
