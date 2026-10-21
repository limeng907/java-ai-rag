# V8.8 Grounding 与证据校验测试报告

测试日期：2026-10-19（Asia/Tokyo）。测试对象：当前工作区实现，包含 V8.7 Runtime、V8.8 Grounding 及 V8.9 记忆逻辑。

**验收结论：T8-01～T8-38 全部通过，未发现本轮结构化证据验收的阻断问题。** 同时确认一个明确边界：结构化事实为 45 分时，模型仍可在自由文本 `reason` 中写成 90 分而通过当前校验。此现象已用测试复现，不应把本报告理解为全面自然语言事实正确性保证。

## 1. 执行汇总

| 测试类 | 本轮执行 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| AgentEvidenceValidatorTest | 21 | 0 / 0 / 0 |
| V88GroundingPostgresTest（本轮新增） | 21 | 0 / 0 / 0 |
| V8PostgresTest（原有数据库、事务与记忆回归） | 77 | 0 / 0 / 0 |
| AgentRuntimeTest | 20 | 0 / 0 / 0 |
| ToolPolicyTest | 4 | 0 / 0 / 0 |
| MemoryContextTest | 8 | 0 / 0 / 0 |
| InterviewPolicyTest | 7 | 0 / 0 / 0 |
| **合计** | **158** | **0 / 0 / 0** |

本轮新增 29 个测试方法：证据校验单测 8 项、独立 Grounding 数据库测试 21 项。首次专项运行 40 项通过后，补强了完整快照、Prompt Injection 和旧 JSON 兼容验证，最终执行上述 158 项。最终测试及首次打包尝试耗时约2分21秒；初次专项运行次数不重复计入合计。

**构建情况单独记录：** 158项测试全部通过后，默认目录的 Spring Boot repackage 无法将 JAR 重命名为 `.jar.original`，该次 Maven 命令因此以 BUILD FAILURE 结束。随后用临时POM仅调整输出目录和文件名，在 `target/v88-package` 执行 `package -DskipTests`，18.5秒后 BUILD SUCCESS。这里跳过的是已完成的测试重跑，不改变上表“158项、0跳过”的实测统计。已将生成的可执行JAR复制回默认路径，检查 Runtime class 与已测试构建一致、Prompt已恢复，保留正在运行的开发服务；临时POM已删除。

测试使用 JUnit 5、Mockito、真实 Spring AI ToolCallingManager、真实 PostgreSQL 事务。ChatModel 和向量候选检索使用固定模拟结果；所有异常由测试主动注入，不依赖 DeepSeek 随机犯错，也未增加第二次模型审查。

本轮没有重新调用真实 DeepSeek 或运行 Postman：这里验证的是可重复的证据规则与并发一致性。上一轮真实接口/模型结果仍见 [V8.7 报告](v87-test-report-2026-10-19.md)，不计入本轮通过数。

## 2. 环境、隔离与数据

- Java 21.0.9、Maven 3.9.11、Spring Boot 3.5.5、Spring AI 1.1.8。
- 独立临时 PostgreSQL：`ai-java-v88-acceptance-pg`，本机端口 `55439`，数据库 `v88_acceptance`。
- 每套数据库测试在独立随机 `v8_test_*` 或 `v88_test_*` schema 内应用 001～006 迁移，结束后清理。没有写入已有业务数据库。
- 完整回归中的真实 Redis 用例使用专用容器 `ai-java-v88-acceptance-redis`，本机端口 `56380`；其余故障注入使用模拟 Redis。
- 证据粒度用例使用 S101 第 3 轮 / turn 301（45 分、错误）、S102 第 2 轮 / turn 302（跳过）、S103 第 1 轮 / turn 303（90 分、正确）。S101 另有第 4 轮 / turn 304，验证同场多轮来源；S104 仅有整场评价，验证旧数据粒度。
- 用户隔离使用用户 1/2；知识库用例创建独立文档 V1/V2 与真实数据库 Chunk 行。其他方法按测试隔离重新构建夹具，不依赖上一用例的记录。

本轮只新增或修改测试、报告及证据采集脚本。未修改生产业务实现；Prompt 测试临时修改的是实际加载的 `target/classes/prompts/chat.txt`，在 `finally` 中恢复，源资源不变。

## 3. 四个问题分别如何验证

| 问题 | 验证方法 |
| --- | --- |
| 工具是否返回真实记录 | 向 PostgreSQL 写入已知分数、轮次、跳过状态与知识正文，逐字段比对 Repository/AgentTools 返回值；向量候选返回伪正文时，最终工具仍取数据库的正文。 |
| 是否正确注册证据 | 第二次模型调用开始时，另一数据库连接已可查询对应证据行，关联到同一 execution、tool call 和业务 source；快照中途写失败时整批回滚。 |
| 证据是否支持结构化事实 | 分别修改 score、correct、round、turnId、粒度、证据类型、题目关联或来源版本；验证拒绝，而不仅检查 ID 是否存在。 |
| 保存时是否仍有效 | 用 CountDownLatch 控制 Agent 已读取 V1 后暂停，另一线程提交发布/删除/重新索引，再释放 Agent；最终业务提交拒绝旧引用，没有计划或消息残留。 |

## 4. T8-01～T8-38 逐项结果

测试简称：**G** = `V88GroundingPostgresTest`；**V** = `AgentEvidenceValidatorTest`；**P** = `V8PostgresTest`。对应方法名均可在交付 XML 中找到。

| 编号 | 结果 | 实测内容与主要用例 |
| --- | --- | --- |
| T8-01 | 通过 | G `t801To805GranularityAndMultipleRoundsHaveExactBusinessIdentity`：S101/turn301/第3轮/45分/false/缺失知识点与数据库一致。 |
| T8-02 | 通过 | 同上：S102 保留 `skipped=true`，注册为 INTERVIEW，不注册为 WEAKNESS；不把“未作答”转换成能力不足。 |
| T8-03 | 通过 | 同上及 V `legacySummaryCannotBePresentedAsTurnScore`：旧面试注册 `SESSION_SUMMARY`，无 turnId、逐轮 score；伪装成逐轮事实被拒绝。 |
| T8-04 | 通过 | 同上及 V `skipAndHighScoreCorrectTurnAreNotAbilityWeaknessEvidence`：90 分且正确的 turn303 不进入确定薄弱点；整场摘要不冒充这条高分轮次。 |
| T8-05 | 通过 | G 同一粒度用例：S101 第3/4轮各自保留 turnId、round 与 `101/turn/<turnId>` 来源，不互相覆盖。 |
| T8-06 | 通过 | G `t806And807SnapshotAndToolForeignIdentityExistBeforeModelSeesEvidence`：实际工具结果注册 W1，快照字段、业务来源及工具调用关联正确。 |
| T8-07 | 通过 | 同上：模型收到 W1 时，持久化证据和工具 SUCCEEDED 均已可查询；随后引用 W1 并成功提交。 |
| T8-08 | 通过 | G `t808SecondSnapshotInsertFailureRollsBackFirstSnapshotAndToolSuccess`：W2 插入触发约束异常，之前写入的 W1 一起回滚；工具 FAILED、output 为空，模型仅调用一次，未收到未持久化的编号。 |
| T8-09 | 通过 | G `t809ToolFailureCreatesNoEvidence`：工具业务查询抛异常，证据行数为 0，工具 FAILED。 |
| T8-10 | 通过 | G `t810InterruptIgnoringLateToolCannotRegisterEvidence`：工具忽略中断并在超时后返回真实候选；迟到结果无证据注册，执行 TIMED_OUT。 |
| T8-11 | 通过 | G `t811SameW1PersistsIndependentSnapshotsPerExecution`：两个 execution 分别拥有 W1，快照 score 分别为 45/30，互不覆盖；V 另验证相同短 ID 不能支持另一执行的事实。 |
| T8-12 | 通过 | G `t812DuplicateSnapshotDeliveryCannotOverwriteOrPartiallyAppend`：重复持久化被唯一键拒绝，原快照不变、无部分新增；`t812RepeatedToolWithNewCallIdKeepsUnambiguousSnapshots` 验证合法再次查询分别注册 W1/W2，并对应 w1/w2。 |
| T8-13 | 通过 | G `t813AllAllowedSnapshotContentSurvivesActualAuditTruncation`：工具审计实测截为8192字符；8条证据的完整允许正文、尾部标记、contentHash、toolCallId 逐一正确。 |
| T8-14 | 通过 | V `missingForeignOrCrossTypeIdsCannotSupportWeakness`：不存在的 W999 被拒绝。 |
| T8-15 | 通过 | G `t815EarlierExecutionEvidenceUnavailableWithoutCurrentTool`：即使同一会话历史有 W1，新执行不调用工具直接复用，最多修正一次后拒绝；未新增消息或证据。 |
| T8-16 | 通过 | V `realIdCannotSupportWrongScoreCorrectnessOrRound`、P `recordedIdWithWrongScoreIsRejectedAndCanBeRepairedOnlyOnce`：真实45分改成90分被拒绝；修正后方可保存，持续错误不保存。 |
| T8-17 | 通过 | V `t817CorrectnessMismatchIsRejectedWithoutChangingAnyOtherFact`：只把 correct=false 改成 true，其他字段保持正确，仍被拒绝。 |
| T8-18 | 通过 | V `legacySummaryCannotBePresentedAsTurnScore`：整场摘要不得填入某轮 turnId、score 等未知事实；仅按摘要粒度引用可接受。 |
| T8-19 | 通过 | V `skipAndHighScoreCorrectTurnAreNotAbilityWeaknessEvidence`、P `skippedTurnIsEvidenceOfNonAnswerNotInability`：跳过证据不能用作确定错题/能力薄弱证据。 |
| T8-20 | 通过 | V `validTurnFactsAreVerifiedAndCompletedByBackend`、`t820OmittedFactsAreFilledFromBackendRatherThanInvented`：正确引用通过；省略的事实由后端补全45分、false、turnId、round。G 进一步验证实际提交。 |
| T8-21 | 通过 | G `t821PersonalDiagnosisWithoutEvidenceCanRepairToBasicPlan`：已执行必要工具但没有个人面试证据时，捏造诊断被拒绝；一次修正可生成 weaknesses=[] 的7天基础计划。 |
| T8-22 | 通过 | P `publicKnowledgeHasHashIdentityWithoutInventedPrivateDocumentVersion`：公共来源为 PUBLIC，有真实 source/chunk/hash，documentId/version 为空。 |
| T8-23 | 通过 | G `t823And824PrivateKnowledgeRechecksOwnerAndUsesDatabaseContent`：当前用户、明确选定知识库、已发布 V1 返回成功，正文取自数据库。 |
| T8-24 | 通过 | 同上：用户2访问用户1文档失败；即使向量层返回了私有候选，也无法通过 AgentTools 的权限复核获取正文。 |
| T8-25 | 通过 | G `t825UnpublishedLifecycleStatesCannotBecomeEvidenceEvenIfVectorReturnsThem`：UPLOADING、UPLOADED、QUEUED、PROCESSING、FAILED 未发布文档均不能成为有效知识来源。 |
| T8-26 | 通过 | G `t826And827OnlyActivePublishedVersionSurvivesSwitch`：V1已发布、V2 PROCESSING 且未发布时，即使候选含两版本，也只返回 V1。 |
| T8-27 | 通过 | 同上：事务内发布V2并切换 active_version 后，新检索仅返回V2，不接受V1。 |
| T8-28 | 通过 | G `t828ConcurrentV2PublicationRejectsOldPlanAtCommit` / `t828ConcurrentDeletionRejectsOldPlanAtCommit`：真实双线程按闩锁顺序运行，发布/删除先提交，Agent 随后保存失败，0计划、0消息、FAILED/6003。P 另验证提交阶段读锁阻止发布越过事务边界。 |
| T8-29 | 通过 | G `t829ConcurrentReindexRejectsOldChunkAndNewLookupFindsSameBusinessVersion`：同版本旧Chunk删除、新Chunk替换后，旧引用不能保存；新查询仍能返回相同documentId/version和新chunkId。 |
| T8-30 | 通过 | G `t830ValidPrivateCitationCommitsPlanAndImmutableSourceSnapshot`：有效V1引用保存成功，状态FINISHED；快照保留真实正文、文档、版本、Chunk和哈希。 |
| T8-31 | 通过 | V `t831ExistingEvidenceCannotBeReassignedToNonexistentQuestion`：不能把真实Q1重新指向999；P `fabricatedQuestionIdIsRejectedAndNoPlanIsSaved` 验证无计划落库。 |
| T8-32 | 通过 | V `questionRecommendationNeedsQuestionToolAndTopicMatchNotJustReason`：HashMap题目匹配HashMap主题时允许；P `studyUsesToolsSavesPlanAndRejectsOtherUsers` 验证从真实题目表取证并保存7天计划。 |
| T8-33 | 通过 | V 上述题目用例与 `t833To835ReasonsCannotReplaceUncertainOrConflictingTopicEvidence`：仅有“适合用户”的理由，缺少匹配证据不能通过。 |
| T8-34 | 通过 | 同上：CSS flexbox题目与HashMap主题冲突，拒绝推荐。 |
| T8-35 | 通过 | 同上：仅泛化到Java、无法明确关联时拒绝该推荐；移除questionIds/recommendations后，基础计划仍可通过。 |
| T8-36 | 通过 | G `t836PoisonedDocumentIsVisibleAsUntrustedDataButCannotChangeUserIdentity`：恶意正文确实到达模型且标记为不可信数据；模拟模型请求userId=2被参数门禁拒绝，历史工具未执行、未保存消息。 |
| T8-37 | 通过 | G `t837ChangedLocalPromptDoesNotOverrideActiveDatabaseVersion`：修改实际加载的本地Prompt后，活动数据库版本与模型Prompt均不变；停用数据库活动版本才读取本地fallback。测试后恢复。 |
| T8-38 | 通过 | V `t838EachRequiredEvidenceFieldIsCheckedSeparately` 与 G `t838LegacyStoredPlanReadableButSameNewOutputRejected`：缺evidenceIds、facts或quote的新结果被拒绝；实际不含新字段的旧JSON计划仍可读取，不能作为新结果重新通过保存。 |

## 5. 关键证据与实际表结构

`agent_evidence` 的主键是 `(execution_id, evidence_id)`，没有独立 `status` 列。工具状态来自 `agent_tool_call`，执行状态来自 `agent_execution`。实际可用的检查SQL是：

```sql
SELECT e.execution_id, e.tool_call_id, e.evidence_id,
       e.evidence_type, e.source_id, e.record,
       t.status AS tool_status, x.state AS execution_state
FROM agent_evidence e
JOIN agent_execution x ON x.id = e.execution_id
JOIN agent_tool_call t ON t.execution_id = e.execution_id
                     AND t.tool_call_id = e.tool_call_id
WHERE e.execution_id = :execution_id;
```

快照写入失败验证的是“部分证据已经插入后再失败”，不只是让整个数据库不可用。成功工具状态与证据处于同一事务，约束异常后均不能留下虚假成功。

重复交付的当前语义是**拒绝重复写入并保留原快照**，不是把任意重复请求都当作成功的幂等API。W1是执行内编号，不是全局身份；必须同时使用executionId定位证据。

文档并发测试的精确顺序：

1. Agent 获取并持久化 V1 的 K1，进入下一次模型调用后暂停。
2. 另一线程获取发布锁，提交 V2 发布 / 文档删除 / 同版本Chunk替换事务。
3. 闩锁释放 Agent，返回引用旧 K1 的合法结构化计划。
4. 提交时复核拒绝旧引用，状态 FAILED、错误码6003；不保存消息或计划。旧快照保留为失败执行的审计记录，不能因此用于新执行。

对应实测快照：

- [注册顺序与工具关联](v88-evidence/registered-evidence.json)
- [并发发布V2后的拒绝记录](v88-evidence/concurrent-publish.json)
- [并发删除后的拒绝记录](v88-evidence/concurrent-delete.json)
- [同版本重新索引后的拒绝记录](v88-evidence/concurrent-reindex.json)
- [有效私有引用成功提交](v88-evidence/committed-private-citation.json)
- [8条未受审计截断影响的快照](v88-evidence/untruncated-snapshots.json)

## 6. 已确认的边界

1. **自由文本不受完整事实校验。** `freeTextReasonIsNotAnIndependentStructuredFactGuarantee` 故意构造事实score=45、reason="The recorded score was 90"，当前校验接受，后端输出的结构化score仍为45。这是能力边界的特征测试，不代表错误文字被证明正确。展示分数、对错、跳过状态时应以后端facts为准。
2. **已保存评分并非绝对客观真相。** 系统验证的是模型是否忠实引用存储记录；证据带有 `RECORDED_ASSESSMENT_NOT_OBJECTIVE_TRUTH` 标记，不能据此证明原始AI评分准确。
3. **相关性规则有限。** 题目/主题以明确词项或片段关联为主；本轮覆盖明确匹配、明显冲突与无法确定情形，没有证明能理解所有自然语言语义或抵抗所有Prompt Injection变体。
4. **快照完整性有输入边界。** 独立快照保存的是工具允许返回的证据，不是无限长原始文档；真实审计8192字符截断不影响该范围内的快照完整性。
5. **数据库是真实的，检索候选是模拟的。** 本轮验证权限、发布、版本、哈希及提交复核，不覆盖文件上传、Embedding质量、真实向量召回率或对象存储文件处理。并发发布通过真实Repository发布操作和PostgreSQL事务完成，没有运行后台文件索引器。
6. **超时与负载范围有限。** 迟到工具测试使用缩短的超时做确定性断言；完整回归另外重新执行原有20秒/60秒边界。没有进行长期压力或真实模型成功率统计。

## 7. 复现、产物与清理

准备独立 PostgreSQL/Redis 后运行：

```powershell
$env:V8_TEST_JDBC_URL='jdbc:postgresql://localhost:55439/v88_acceptance?connectTimeout=10&socketTimeout=20'
$env:V8_TEST_JDBC_PASSWORD='v88-test-only'
$env:V87_TEST_REDIS_PORT='56380'
mvn -o -s tools/maven-central-settings.xml -gs tools/maven-central-settings.xml test '-Dtest=AgentRuntimeTest,ToolPolicyTest,AgentEvidenceValidatorTest,MemoryContextTest,InterviewPolicyTest,V8PostgresTest,V88GroundingPostgresTest'
python tools/collect-v88-evidence.py
```

环境变量沿用现有测试命名；测试自身负责创建隔离schema和应用迁移。真实Redis用例未启用时会跳过，本轮显式启用了它。

- [最终运行日志](v88-evidence/acceptance-run.log)
- [独立目录打包成功日志](v88-evidence/package-run.log)
- [测试数、时间与源码SHA-256](v88-evidence/manifest.json)
- [证据校验JUnit明细](v88-evidence/TEST-com.shtbs.interview.agent.AgentEvidenceValidatorTest.xml)
- [新增Grounding数据库JUnit明细](v88-evidence/TEST-com.shtbs.interview.agent.V88GroundingPostgresTest.xml)
- [原有数据库回归JUnit明细](v88-evidence/TEST-com.shtbs.interview.agent.V8PostgresTest.xml)
- [新增Grounding测试源码](../../src/test/java/com/shtbs/interview/agent/V88GroundingPostgresTest.java)
- [证据采集脚本](../../tools/collect-v88-evidence.py)

仅归档本轮实际执行的7个测试类，未将目录中旧的真实模型XML混入统计；归档XML已移除JVM属性。本次临时数据库、Redis和测试schema已清理，原开发服务保持运行。
