# V8.9 AgentMemory 测试报告

测试日期：2026-10-19（Asia/Tokyo）。对象：当前工作区源码中的短期消息、历史摘要、上下文预算与删除隔离。跨会话 pgvector 语义记忆属 V8.10，未列入验收。

**结论：31 项满足本轮可验证的预期，T9-20 仅验证“缺失细节没有进入模型上下文”，尚不能保证模型自由文本绝不编造。** 回归测试 173 项、真实模型冒烟测试 1 项，合计 174 项，失败 0、错误 0、跳过 0。新增 15 个针对性自动化测试；另复用了现有的摘要、并发、事务及实时接口测试。没有修改生产实现或生产数据。

## 执行环境与证据

| 范围 | 实际执行 | 结果 |
| --- | ---: | --- |
| `MemoryContextTest` | 15 | 全部通过 |
| `V8PostgresTest` | 85 | 全部通过 |
| `AgentRuntimeTest`、`ToolPolicyTest`、`AgentEvidenceValidatorTest`、`InterviewPolicyTest`、`V88GroundingPostgresTest` | 73 | 全部通过 |
| `V89MemoryLiveTest`：真实 JWT/HTTP/DeepSeek | 1 | 通过 |
| **合计** | **174** | **0 失败、0 错误、0 跳过** |

使用 Java 21、JUnit 5、Mockito、独立 PostgreSQL 容器 `ai-java-v89-acceptance-pg`（本机 55440，库 `v89_acceptance`）和独立 Redis 容器 `ai-java-v89-acceptance-redis`（本机 56381）。数据库测试在随机 `v8_test_*` schema 中执行；真实 HTTP 测试连接测试库的 `public` schema。真实模型测试使用专用 18201 端口进程、临时用户和会话，测试结束清除相关记录与缓存。常用开发服务 8200 和开发库未用于本轮写入。

回归的模型异常场景用模拟 `ChatModel`，以便稳定地控制超时、摘要失败、并发更新等条件。实时测试在 9 轮预置对话后调用 `/agent/chat` 触发后台摘要，删除对应 Redis Key，再追问早期目标；它断言摘要已入 PostgreSQL、边界覆盖前 10 条消息、恢复回答包含早期计划名称和每日 45 分钟、原始 22 条消息仍在，最后调用删除接口验证摘要与缓存被清理。实时测试只检验这一个流程，不能替代异常场景测试。

可核对的原始结果见 [证据目录](v89-evidence/manifest.json)：每个 JUnit XML 已移除系统属性，另保留定向、全量回归和实时测试日志，以及源码 SHA-256。执行命令和用例名单也在这些文件中。

## T9-01～T9-32 逐项结果

表中 `P` 指 `V8PostgresTest`，`C` 指 `MemoryContextTest`，`L` 指 `V89MemoryLiveTest`。除明确写为“部分验证”的 T9-20 外，结论均为通过。

| 编号 | 结果 | 实测依据 |
| --- | --- | --- |
| T9-01 | 通过 | P `t901To903FirstChatPersistsBothMessagesThenRefreshesRealRedis`：新会话缓存缺失，模型收到 system 与当前问题，无伪造历史。 |
| T9-02 | 通过 | 同上：业务提交后 `agent_message` 按序有 USER、ASSISTANT 两条，执行为 `FINISHED`。 |
| T9-03 | 通过 | 同上：提交后真实 Redis Key 存在，缓存版本为 2，包含两条消息。 |
| T9-04 | 通过 | P `t904T905T907T909T931RealCacheHitExpiryMissAndVersionMismatch`：第二次读取命中 Redis，监视的 `MemoryRepository.messages()` 未再次执行。 |
| T9-05 | 通过 | 同上：Key 过期后，历史从 PostgreSQL 回源并回填。L 又验证手动删 Key 后真实接口仍恢复早期目标。 |
| T9-06 | 通过 | P `t906UnavailableRedisEndpointStillReadsPostgresHistory`：Redis 指向不可连接的本机端口，仍读到数据库中的 4 条消息。 |
| T9-07 | 通过 | P 缓存过期后读到全部 4 条记录；数据库原始消息未删除。 |
| T9-08 | 通过 | P `t908T929T930DifferentUsersAndDeletedConversationCannotLeakCachedMemory`：A/B 不同 Key，双方互读均被权限校验拒绝。 |
| T9-09 | 通过 | P 注入旧版本缓存后追加新消息：加载以数据库 `message_version=6` 为准，回源并返回 6 条，未继续使用旧缓存。原有 `memoryStaleCacheIsRejectedAfterRefreshFails` 覆盖刷新失败。 |
| T9-10 | 通过 | P `t910RealRedisTtlsAreSpreadAcrossConversations`：12 个真实 Key 的 TTL 均在 5400～9000 秒范围，至少 10 个不同值；`t910ReadConcurrencyIsBoundedAtEight` 将回源并发占满 8 个名额，第 9 个请求收到明确限制错误。该测试证明上界和 TTL 分散，没有做大规模压测。 |
| T9-11 | 通过 | C `t911And921FourShortMessagesNeedNoSummaryOrEviction`：4 条短历史完整进入上下文；P `memoryFirstConversationHasNoSummaryOrHistory` 验证新会话无摘要。 |
| T9-12 | 通过 | P `memorySummarizesAtTwentyAndIncrementallyRetainsTen`：达到 20 条未摘要消息后执行摘要。 |
| T9-13 | 通过 | P `t913T914T916FortyMessagesProduceThirtyMessageBoundaryThenIncrementalSummary`：40 条历史摘要前 30 条；加载得到摘要加最近 10 条。C `t913SummaryAndTenUncoveredMessagesMakeTheExactPrompt` 核对模型消息顺序和内容。 |
| T9-14 | 通过 | 同一 P 用例检查 `agent_memory_summary` 的非空摘要、`last_message_id` 等于第 30 条消息 ID、初始 `version=1`。 |
| T9-15 | 通过 | P `memorySummaryFailureDoesNotAdvanceOrLoseMessages`、`memoryInvalidSummaryCannotAdvanceBoundary`、`memoryLateTimedOutProviderCannotSaveSummary`：失败/迟到结果不推进边界或版本，原消息保留。 |
| T9-16 | 通过 | P 的 40→50 条消息用例：第二次摘要模型输入含 `OLD_SUMMARY_DATA`，版本增至 2，边界只前进到当次实际纳入的完整轮次。 |
| T9-17 | 通过 | P `memoryConcurrentInitialAndStaleUpdatesCannotOverwriteNewerSummary`：并发摘要与过期版本更新不能覆盖新摘要。 |
| T9-18 | 通过 | C `t918To920RecentAndSummarizedFactsAreVisibleButOmittedDetailsAreAbsent`：最近一轮的 HashMap 内容在实际组装的 Prompt 内。 |
| T9-19 | 通过 | C 检查摘要中保留的 Spring 事务传播与每日学习时长可见；L 在真实模型、真实数据库与删缓存后，回答恢复早期计划名称及 45 分钟。 |
| T9-20 | **部分验证** | C 验证不在摘要/近期消息中的旧细节没有进入模型上下文。当前没有可重复的自由文本事实校验来保证真实模型绝不猜测；不能将“上下文没有该细节”等同于“回答必定明确说不知道”。 |
| T9-21 | 通过 | C 的 4 条短消息用例：预算充足时消息数与内容不变。 |
| T9-22 | 通过 | C `t922And923OversizeOldRoundIsRemovedWhileRecentRoundSurvives`：先移除很长的旧轮次，最近轮次和当前问题保留。 |
| T9-23 | 通过 | 同上：一条超长旧回复不能挤占整个上下文。 |
| T9-24 | 通过 | P `t924ManyLongKnowledgeChunksAreCappedButKeepSourceIdentity`：12 个长候选限制为 8 个，每段展示 2000 字符，同时保留真实 chunk/source/scope 与原始内容哈希；注册的证据仍与来源对应。 |
| T9-25 | 通过（代码路径与回归） | `AgentRuntime` 主循环在每次 `model.call` 前执行 `tokens.fit` 和字符预算检查；C `requiredToolProtocolIsNeverCutToMakeItFit` 与 `t926SystemCurrentQuestionAndToolEvidenceCannotBeSilentlyDropped` 验证后续工具消息超限时拒绝。 |
| T9-26 | 通过 | C `t926SystemCurrentQuestionAndToolEvidenceCannotBeSilentlyDropped`：System、当前问题、必要工具证据本身超限返回 6002；三者未被静默裁掉。 |
| T9-27 | 通过 | C `t927LargePlanJsonIsProjectedWithoutStructuredEvidenceOrRecommendations`：历史助手大型 JSON 被投影为短对话文本与计划主题，测试输入缩短超过 10 倍，旧引用和题目 ID 不再进入新 Prompt；原始业务记录继续保留在 PostgreSQL。 |
| T9-28 | 通过 | L 调用真实删除接口后摘要表无该会话行、Redis Key 不存在；P `memoryDeleteCommitsDuringRedisFailureAndCleanupRetries` 验证故障时补偿清理。 |
| T9-29 | 通过 | P `t908T929T930DifferentUsersAndDeletedConversationCannotLeakCachedMemory`：B 的摘要用 A 身份保存失败，跨用户会话读取被拒绝；原有 `memoryOwnershipIsCheckedEvenOnCacheHit` 覆盖缓存命中时的权限校验。 |
| T9-30 | 通过 | P 删除会话后手工重新放入旧 Redis 值，读取仍因数据库会话不存在而拒绝；Redis 不能复活已删除记忆。 |
| T9-31 | 通过 | P 旧版本缓存与数据库 `message_version` 不一致时回源；过期版本不被使用。 |
| T9-32 | 通过 | C `t932OldFormatAndPartialPairNeverImportAnotherExecutionAsAValidRound`：旧格式坏 JSON 被显式标记；不完整或跨 execution 的消息不被拼成一轮。此测试不等于对任意数据库损坏都能恢复。 |

## 验收边界与后续问题

V8.9 的持久化、缓存失效/回源、摘要边界与并发防覆盖、必要上下文保护，在上述测试范围内达到预期。T9-20 是唯一不能宣称完全满足的语言行为：当早期细节已不在摘要或近期消息里，后端可以避免把错误“记忆”塞入 Prompt，但目前没有确定性机制验证模型最终措辞是否编造。若把“不编造”作为严格上线门槛，应另加可验证的回答策略或结构化未知状态，并以模拟模型及真实模型分别验收。

Redis 故障回退用不可连接端口模拟，没有停掉共享 Redis；并发回源测试验证了 8 个名额的上界，没有做吞吐量基准。真实模型冒烟只走单次正向流程，其余异常场景由可重复的模拟模型和真实 PostgreSQL/Redis 测试覆盖。
