# PostgreSQL 迁移与文档生命周期

## 本机启动

1. PostgreSQL 需启用 `vector` 扩展，并保留现有 `java_rag.vector_store`。先备份 MySQL 和 PostgreSQL；业务表迁移前在 `java_rag` 执行 `db/postgresql/001_business_schema.sql`。此脚本建立 `app_user`（原 MySQL `user`）、`question`、`interview_session`、`interview_message`、`interview_evaluation`。
2. `tools/MigrateMySqlToPostgres.java` 是一次性迁移器，需要 `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`、`POSTGRES_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD` 环境变量。目标业务表必须为空；迁移器在单个 PostgreSQL 事务里复制、核对各表行数并重设 ID 序列。不要对已迁移的库再次运行。
3. 在同一库执行 `db/postgresql/002_document_lifecycle.sql`，建立知识库、文档、文档版本三张表。已有 `vector_store` 不会被重建或清空。运行当前 V8 代码还需执行一次 `db/postgresql/003_agent_platform.sql`；本机已完成该迁移，详见 [V8 实施文档](v8-implementation.md)。
4. 新建本地 `.rustfs.env`，设置 `RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY`，运行 `docker compose -f compose.rustfs.yml up -d`。对象数据保存在 Docker 命名卷中。RustFS S3 API 为 `127.0.0.1:9000`，控制台为 `127.0.0.1:9001`。
5. 在本地 `.local-secrets.yml` 或环境变量中提供 `POSTGRES_PASSWORD`、`RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY`，以及项目原有的邮件和 AI 凭据。不要提交这些文件。Redis 与 Ollama 分别需在 `127.0.0.1:6379`、`127.0.0.1:11434` 可用；安装 `nomic-embed-text` 用于 768 维向量。
6. 用户已明确授权文档问答使用 DeepSeek。`/knowledge/chat` 通过现有 Spring AI `ChatClient` 调用 `https://api.deepseek.com`，使用 `AI_API_KEY` 和 `spring.ai.openai.chat.options.model` 配置。检索先校验知识库所有者，只把当前用户在该库中检索到的已发布片段及问题发送给 DeepSeek。Ollama 继续负责本地向量化，无需本地聊天模型；上游调用失败时返回 HTTP 502 和业务码 5010。配置 `spring.http.client.factory=jdk`，解决本机默认 HTTP 客户端读取 DeepSeek 分块 JSON 响应不完整的问题。
7. 运行 `mvn -s tools/maven-central-settings.xml -gs tools/maven-central-settings.xml test`，再运行同样 settings 参数的 `spring-boot:run`。本机 Maven 镜像故障时，该 settings 文件可使用 HTTPS Maven Central。

## API 与状态

所有知识库接口都使用原有 `Authorization: Bearer <token>` 登录态，知识库只对所有者可见。`POST /knowledge-bases` 创建，`GET /knowledge-bases` 列出，`GET /knowledge-bases/{id}` 查看，`DELETE /knowledge-bases/{id}` 删除空知识库。

`POST /knowledge-bases/{baseId}/documents` 用 multipart 字段 `file` 上传；支持不超过 10 MB 的 PDF、DOCX、UTF-8 TXT、MD。`GET /knowledge/documents?knowledgeBaseId=...` 列出，`GET /knowledge/documents/{id}` 查状态，`GET /knowledge/documents/{id}/versions` 查版本，`GET /knowledge/documents/{id}/chunks?version=...` 查切块，`GET /knowledge/documents/{id}/download` 下载当前活动原件。

`PUT /knowledge/documents/{id}/file` 上传新版；`POST /knowledge/documents/{id}/reindex` 用当前活动原件重建索引；`POST /knowledge/documents/{id}/retry` 重试解析或向量化失败的最新版；`DELETE /knowledge/documents/{id}` 异步删除。`POST /knowledge/search` 和 `POST /knowledge/chat` 使用 JSON `{"knowledgeBaseId":1,"question":"..."}`。

上传成功后文档经历 `UPLOADING → UPLOADED/QUEUED → PROCESSING → READY`，失败版本为 `FAILED`。新版达到 `READY` 后才切换 `active_version`；新版失败时旧版继续提供下载与检索。删除先撤销向量发布，再异步移除向量和 RustFS 对象，最终标为 `DELETED`。Redis Stream 传递任务，数据库状态用于重启后对账；原 `vector_store` 演示向量保留，私人向量按知识库、用户和发布状态过滤。

V8 加入按处理令牌隔离的向量写入、发布与清理，避免过期任务影响新任务结果；超时的 `UPLOADING` 版本也会检查对象存储并恢复入队或标记失败。私人知识库权限继续按所有者校验，公共知识同步 `/knowledge/import` 现在需要管理员角色。

## 本次迁移验证

迁移前 MySQL 五张业务表共有 19 行；迁移后 PostgreSQL 分别为 `app_user=5`、`question=2`、`interview_session=6`、`interview_message=5`、`interview_evaluation=1`，原有 `vector_store=7` 行保留。备份存放在本机 `.migration-backups/`，该目录不应提交。测试账号和测试文档应在测试后删除，不计入以上历史数据。

本地端到端测试覆盖 TXT、Markdown、PDF、DOCX 的上传、解析、切块和检索；损坏 PDF 的失败与重试、旧版保留、新版替换、重新索引、跨用户拒绝访问、异步删除和 RustFS 对象清理也已验证。

DeepSeek 问答实测已通过：使用合成发布文档提问，HTTP 200 返回预期校验码 `DS-RAG-7429`，来源为 `deepseek-probe.md`；项目正式配置重启后复测仍通过。空知识库与删除文档后的知识库返回无资料提示和空来源，其他用户问答返回 404，未登录问答返回 401。上游响应解析异常返回 HTTP 502，修正 HTTP 客户端后实际生成恢复正常。测试文档、知识库和临时账号已清理。
