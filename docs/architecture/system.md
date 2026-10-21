# 系统架构与证据流程

## 整体架构

```mermaid
flowchart LR
  U[用户或验收脚本] --> A[Spring Boot API]
  A --> P[(PostgreSQL 业务表)]
  A --> V[(pgvector 向量表)]
  A --> R[(Redis Stream)]
  A --> S[(RustFS 原始文档)]
  R --> W[文档 Worker]
  W --> S
  W --> O[Ollama Embedding]
  W --> V
  A --> L[DeepSeek 模型]
  A --> E[Agent 证据与版本复核]
  E --> P
  E --> V
```

Spring Boot 负责鉴权、面试业务、Agent 执行和文档生命周期。用户所有权、文档版本、已发布状态和引用核对在后端完成。Ollama 负责 Embedding；DeepSeek 负责聊天、面试分析和学习计划生成。

## RAG 文档生命周期

```mermaid
sequenceDiagram
  participant User as 用户
  participant API as Spring Boot
  participant DB as PostgreSQL
  participant S3 as RustFS
  participant Q as Redis Stream
  participant Worker as Worker
  participant Model as Ollama
  User->>API: 上传 Markdown/PDF/TXT
  API->>DB: 建立文档与 V1 UPLOADING
  API->>S3: 保存原始文件
  API->>Q: 发布索引任务
  API-->>User: 返回文档 ID 与状态
  Q->>Worker: 消费任务
  Worker->>S3: 读取原文
  Worker->>Model: 切块后生成向量
  Worker->>DB: 写入未发布 Chunk
  Worker->>DB: 原子切换 active_version 与 published
  Worker->>Q: ACK
  User->>API: 查询 READY 状态
```

失败版本不会自动成为活跃引用。Worker 对未确认任务执行重试和数据库状态对账。替换文件时仍可读旧版，直到新版成功发布；删除时先撤销发布，再清理向量与对象。

## Agent 执行和证据校验

```mermaid
flowchart TD
  Q[用户请求] --> O[鉴权与选定知识库]
  O --> M[模型决定工具调用]
  M --> T[后端只读检索与业务工具]
  T --> G[记录 Evidence ID 与来源快照]
  G --> A[模型生成回答或计划]
  A --> C[后端校验引用与结构化事实]
  C --> V{版本和所有权仍有效?}
  V -- 是 --> D[事务提交消息与计划]
  V -- 否 --> F[拒绝提交]
```

已保存的薄弱点原因和个人评价总述来自后端核对的面试事实；开放式建议仍是模型文本。精确会话记忆当前覆盖每日学习时间与面试公司，只查本会话原始用户消息。其他自然语言没有同等级确定性保证，详见 [稳定化报告](../testing/v8-stabilization-report-2026-10-20.md)。
