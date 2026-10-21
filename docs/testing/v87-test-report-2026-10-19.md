# V8.7 AgentRuntime / Tool Calling 测试报告

测试日期：2026-10-19，约 19:36–19:48（Asia/Tokyo）。

**结论：T7-01～T7-25 均有实际执行的自动化测试覆盖，全部通过；本轮未发现阻断 V8.7 验收的问题。** 当前工作区已经包含 V8.8 证据约束和 V8.9 记忆功能，本报告验证的是当前代码对 V8.7 要求的满足情况，并非切换到历史 V8.7 版本。源码与测试文件 SHA-256 记录在 [manifest.json](v87-evidence/manifest.json)。

## 1. 执行结果

| 层次 / 测试类 | 执行数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| AgentRuntimeTest：模拟 ChatModel、真实 Spring AI ToolCallingManager | 20 | 0 / 0 / 0 |
| ToolPolicyTest | 4 | 0 / 0 / 0 |
| AgentEvidenceValidatorTest | 13 | 0 / 0 / 0 |
| MemoryContextTest | 8 | 0 / 0 / 0 |
| InterviewPolicyTest | 7 | 0 / 0 / 0 |
| V8PostgresTest：真实 PostgreSQL，含新增真实 Redis 用例 | 77 | 0 / 0 / 0 |
| V8LiveSmokeTest：真实 JWT、HTTP、DeepSeek、计划与面试流程 | 1 | 0 / 0 / 0 |
| V89MemoryLiveTest：真实 DeepSeek 摘要、Redis 缓存丢失恢复 | 1 | 0 / 0 / 0 |
| **JUnit 合计** | **131** | **0 / 0 / 0** |
| **Postman 集合，经 Newman 6.2.3 执行** | **19 个请求、38 条断言** | **全部通过** |

前 129 项测试和 `mvn package` 一起完成，总耗时约 2 分 14 秒；2 项真实模型测试约 42.5 秒（包括 Maven 开销）；Newman 集合约 29.7 秒。JUnit 用例数与 Postman 断言数口径不同，不相加。

本轮新增 15 项 JUnit 测试（Runtime 12 项、PostgreSQL 3 项），增强了真实模型计划校验，增加 Postman 集合及测试数据脚本。未修改 `src/main` 业务代码。

## 2. 环境与数据隔离

- Java 21.0.9、Maven 3.9.11、Spring Boot 3.5.5、Spring AI 1.1.8；模型使用当前应用配置的 DeepSeek `deepseek-flash`。
- 专用应用：`http://localhost:18200`；保留原 `8200` 应用。
- 新建 PostgreSQL 容器 `ai-java-v87-acceptance-pg`，本机端口 `55438`，独立数据库 `v87_acceptance`；应用 001～006 迁移。
- 新建 Redis 8.0 容器 `ai-java-v87-acceptance-redis`，本机端口 `56379`。使用独立 Redis 实例隔离数据，无需修改业务 Redis key 前缀。
- PostgreSQL 集成测试继续在随机 `v8_test_<uuid>` schema 内运行。清理前检查：测试数据库和原数据库均无遗留 `v8_test_%` schema。
- 开始时的基线回归在已有 PostgreSQL 的隔离 schema 执行，114 项通过；最终验收在新建测试数据库执行，不重复计入基线次数。

Postman 固定数据来自 [v87-acceptance-fixtures.sql](../../tools/v87-acceptance-fixtures.sql)：

| 数据 | 实际配置 |
| --- | --- |
| 用户 A / B | 1001 / 1002；每日 120 / 60 分钟；经 `/user/login` 获取真实 JWT |
| S101 | 已完成面试，HashMap 逐轮 35 分，存在真实缺失项 |
| S102 | 已完成面试，含跳过记录 |
| S103 | 整场 90 分，Spring 事务逐轮 40 分 |
| Q201 / Q202 / Q203 | HashMap 扩容 / Spring 事务传播 / 无关 CSS 题目 |
| D1 / D2 | 文档 401 的 V1、用户 B 私有文档 402 的 V1/V2，当前版本 V2 |

固定文档是数据库元数据夹具，没有上传对象文件或植入真实向量；文档版本与引用约束由单元/数据库测试验证。真实模型独立用例另外创建随机临时用户及三场面试，结束后清理。未覆盖或改写原有业务记录。

## 3. T7-01～T7-25 验收矩阵

下列方法均在本轮 JUnit XML 中有实际执行记录；“通过”不以源码审查代替运行结果。

| 编号 | 结果 | 测试证据与关键断言 |
| --- | --- | --- |
| T7-01 | 通过 | `t701HistoryLimitThreeUsesAuthenticatedUser`：`limit=3` 调用当前服务端用户历史；Postman 实际 history 工具审计成功。 |
| T7-02 | 通过 | `t702SequentialToolsKeepPreviousResponsesAndCallIds`：先历史、后薄弱点，后续 Prompt 含前轮工具结果与调用 ID。 |
| T7-03 | 通过 | `t703BatchToolResponsesMatchTheirIds`：同轮两个工具都执行，响应 ID 到工具名的映射完全匹配。 |
| T7-04 | 通过 | `t704ChatCanFinishWithoutTools`：合法 CHAT 一次模型调用结束，不启动工具。 |
| T7-05 | 通过 | `missingEvidenceFailsButSuccessfulEmptyHistoryIsAccepted`、`missingMandatoryEvidenceFailsBeforeReservedGeneration`：STUDY 缺必要工具时补充提示，持续缺失最终 6002；成功返回空历史不等于缺失证据。 |
| T7-06 | 通过 | `t706InterviewCannotCallUserProfile`：INTERVIEW 请求档案工具返回 6002，业务方法未调用。 |
| T7-07 | 通过 | `t707UnknownToolCannotExecute`：`deleteAllUsers` 被拒绝，未启动工具；数据库另验证未注册写工具无执行记录。 |
| T7-08 | 通过 | `t708To710InvalidArgumentsNeverReachBusinessTools`：`limit=1000` 返回 6003，未查询历史。 |
| T7-09 | 通过 | 同上：1001 字符 query 被拒绝，VectorStore 无交互。 |
| T7-10 | 通过 | 同上：额外 `userId=1002` 字段被拒绝，不能替换服务端身份；正常工具调用也验证服务端用户 ID。 |
| T7-11 | 通过 | `privateKnowledgeChecksOwnerAndPublishedFilter` 等数据库测试；Postman 用户 A 请求 B 的知识库 302 返回 HTTP 404 / 5001。 |
| T7-12 | 通过 | `t712CumulativeToolBudgetRejectsThirteenthCallBeforeBatch`：5+5+3 累计请求，第三批整体拒绝，仅执行前 10 次；另验证单批 13 次在执行前拒绝。达到 12 次后进入无工具收尾，不执行第 13 次。 |
| T7-13 | 通过 | `t713SixModelCallsNeverBecomeSeven`：4 轮工具、生成、一次修正，共 6 次，最终拒绝且没有第 7 次；另有第 6 次修正成功用例。 |
| T7-14 | 通过 | `t714DefaultTwentySecondTimeoutRejectsLateModelSuccess`：使用真实 20000ms 配置，模拟不响应中断的模型，超时后释放迟到答案，不能进入 VALIDATED；JUnit 整项耗时 21.911s（包含初始化开销）。 |
| T7-15 | 通过 | `t715DefaultSixtySecondTotalDeadlineStopsIndividuallyValidCalls`：每轮模型耗时 16 秒，单次未到 20 秒；第 4 轮受总 60 秒上限终止，仅前 3 个工具启动。整项 60.026s。 |
| T7-16 | 通过 | `t716ToolExceptionIsAuditedAsFailureAndStopsExecution`：注入存储异常，工具审计 FAILED，后续工具和模型调用停止，无成功证据。 |
| T7-17 | 通过 | `toolLoopRechecksTokenBudgetBeforeSecondModelCall`、MemoryContextTest：过大工具结果不能继续提交下一次超额模型请求；验证字符、中文 token、工具定义和输出保留量约束。 |
| T7-18 | 通过 | `invalidOutputIsRepairedOnlyOnceAndRepairCannotUseTools`：错误 JSON 最多修正一次；数据库用例验证修正成功后仅写一组消息。 |
| T7-19 | 通过 | `invalidPlanReferencesCanBeRepairedWithoutPersistingRejectedPlan`、`fabricatedQuestionIdIsRejectedAndNoPlanIsSaved`：合法 JSON 但引用无效时修正或拒绝；伪造题目不保存计划，持续错误事实不保存消息。 |
| T7-20 | 通过 | `t720And725SameConversationRejectsConcurrentRequestThenReleasesLease`：同一 conversation 两请求真实并发，第二个返回 6005，只有第一个进入模型。 |
| T7-21 | 通过 | `studyUsesToolsSavesPlanAndRejectsOtherUsers` 与两组真实 HTTP 计划：成功后 FINISHED、`committed_at` 非空、计划可查询。 |
| T7-22 | 通过 | `failedPlanSaveAlsoRollsBackMessages` 注入保存约束错误；新增 `t722To724DeferredCommitFailureRollsBackFinishedAndPlan` 注入延迟约束触发器，错误确实发生在 COMMIT。两者均无残留计划/消息。 |
| T7-23 | 通过 | `runtimeOnlyValidatesAndBusinessCommitFinishes`：Runtime 只到 VALIDATED。新增提交失败用例在事务内已写 FINISHED 时，通过另一独立连接仍只看到 COMMITTING，未提前对外发布成功。 |
| T7-24 | 通过 | 延迟 COMMIT 错误后持久化状态 FAILED，`committed_at` 为空、`finished_at` 非空；FINISHED 写入随业务事务回滚。 |
| T7-25 | 通过 | 同会话并发用例验证完成后 `busy_token` 为空，后续请求成功；Postman 同会话续聊 HTTP 200。 |

## 4. 真实 HTTP / 模型结果

Postman 使用 [可导入的集合](v87-acceptance.postman_collection.json)，通过 Newman 命令行实际执行，并非仅生成请求文件。每个请求的响应、耗时和断言见 [脱敏结果](v87-evidence/postman-results.json)。

- 未登录调用返回 401；用户 A/B 档案分别为 120/60 分钟。
- B 访问 A 的会话、执行和计划全部返回 404；A 访问 B 私有知识库返回 404，避免泄露资源存在性。
- 正常 chat、study-plans、审计、已保存计划查询和同会话续聊均成功。
- Postman 学习计划执行 ID：`c403a707-ef0b-44ab-959f-7ad23e8990aa`，FINISHED，模型 5 次、工具 12 次，错误码为空。请求耗时 25.294 秒。
- 独立真实模型冒烟计划执行 ID：`e46cd193-07cb-41d5-a804-e6b149b43864`，FINISHED，模型 4 次、工具 11 次，包含三个必要工具的成功审计。7 天时长为 **90、120、100、110、100、90、120 分钟**，均不超过 120；返回 2 个有证据支持的薄弱点。
- 模型没有被要求按固定顺序调用工具。实际出现重复检索，Postman 计划使用了全部 12 次工具预算，但最终正常收尾；这提示预算余量较小，不构成本轮失败。
- 真实摘要测试在删除对应 Redis 缓存后，仍能恢复早期学习目标及每日时长；数据库历史保留完整，删除会话后摘要与缓存清理成功。

上述执行 ID 对应的数据在测试后已清理，离线响应与审计保存在 [live-study-plan.json](v87-evidence/live-study-plan.json) 和 Postman 脱敏结果中。

## 5. 异常、边界与清理

首次沙箱单测启动时，Mockito 创建临时 Java agent 被文件访问权限拒绝，40 项中 9 项报环境初始化错误。改为本机执行后恢复，基线 114 项、最终 129 项回归及 2 项真实测试均通过。没有修改生产代码绕过测试，也没有把首次环境错误算作业务缺陷或最终通过数。

验收结论的边界：

- 这是一轮功能回归和真实模型冒烟，不是高并发压力、长期稳定性或模型成功率统计。
- 77 项数据库测试多数使用模拟 Redis/向量检索以注入故障；新增真实 Redis 缓存回填测试，加上真实应用的摘要恢复测试，覆盖了真实 Redis 链路，不能将 77 项全部称为真实 Redis 故障测试。
- 文档授权、版本和证据快照经过真实数据库测试；没有验证本轮固定 D1/D2 文件的上传、真实向量召回质量或对象存储内容。真实模型的 `searchKnowledge` 调用成功并不等于已验证有文档命中的引用质量。
- 结构化证据事实和引用经过校验，不据此声称对所有自然语言推断做了完整事实判断。
- 超时断言关注迟到结果不能提交；不声称 `Future.cancel(true)` 能立即终止底层网络请求。

测试结束时，独立测试库 `agent_execution`、`agent_message`、`study_plan` 均为 0；仅固定测试夹具保留到容器销毁。本次启动的应用进程和两个临时容器已停止并清理。原应用、PostgreSQL、Redis、RustFS 保持运行。报告与测试源码保留，JWT 未写入交付证据。

## 6. 复现与证据

在同等独立环境中先初始化 001～006 迁移，再执行测试；固定 Postman 数据脚本会检查数据库名必须为 `v87_acceptance`。测试密码仅用于本机临时实例。

```powershell
$env:V8_TEST_JDBC_URL='jdbc:postgresql://localhost:55438/v87_acceptance?connectTimeout=10&socketTimeout=20'
$env:V8_TEST_JDBC_PASSWORD='v87-test-only'
$env:V87_TEST_REDIS_PORT='56379'
mvn -o -s tools/maven-central-settings.xml -gs tools/maven-central-settings.xml package '-Dtest=AgentRuntimeTest,ToolPolicyTest,AgentEvidenceValidatorTest,MemoryContextTest,InterviewPolicyTest,V8PostgresTest'

# 专用应用使用对应 datasource/Redis 配置启动在 18200，加载已有模型凭据。
$env:V8_LIVE_SMOKE_URL='http://localhost:18200'
$env:V89_LIVE_MEMORY_URL='http://localhost:18200'
$env:V89_TEST_REDIS_PORT='56379'
mvn -o -s tools/maven-central-settings.xml -gs tools/maven-central-settings.xml test '-Dtest=V8LiveSmokeTest,V89MemoryLiveTest'

# 在独立数据库装载 tools/v87-acceptance-fixtures.sql 后执行。
node target/v87-newman/node_modules/newman/bin/newman.js run docs/testing/v87-acceptance.postman_collection.json --timeout-request 90000 --reporters cli,json --reporter-json-export target/v87-newman-raw.json
```

原始 Newman JSON 包含登录响应/JWT，应仅留作本地临时文件；本次交付的是经过提取、脱敏的结果，原始文件已删除。采集脚本为 [collect-v87-evidence.py](../../tools/collect-v87-evidence.py)。

- [JUnit 与文件指纹总表](v87-evidence/manifest.json)
- [129 项回归及打包日志](v87-evidence/acceptance-run.log)
- [真实模型测试日志](v87-evidence/live-run.log)
- [Postman 执行日志](v87-evidence/postman-run.log)
- [Runtime JUnit 明细](v87-evidence/TEST-com.shtbs.interview.agent.AgentRuntimeTest.xml)
- [数据库 JUnit 明细](v87-evidence/TEST-com.shtbs.interview.agent.V8PostgresTest.xml)

其余测试类的 XML 同目录保存，已移除与结果无关的 JVM 属性。
