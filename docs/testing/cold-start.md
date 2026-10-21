# Compose 空卷冷启动验收

此清单应在**独立 Compose 项目名**下运行，以免触碰已有开发数据卷。当前工作区尚无一次完整空卷执行结果；以下是待执行的验收步骤和判定条件。

1. 启动 Docker Desktop Linux 引擎，准备 Java 21、Maven、`.env` 中的真实 `AI_API_KEY`、SMTP 授权码和随机测试密码。
2. 运行 `mvn clean verify` 和 `docker compose config --quiet`。
3. 用唯一项目名启动，例如 `docker compose -p ai-java-cold-20261021 up -d --build --wait`。首次 Ollama 拉模可能较久；记录命令退出码和 `docker compose -p ai-java-cold-20261021 ps` 的健康状态。
4. 在隔离数据库中用 `docker compose -p ai-java-cold-20261021 exec -T postgres psql -U postgres -d interview -c "SELECT extname FROM pg_extension WHERE extname='vector';"` 核对 pgvector，再核对 `app_user`、`knowledge_document_version`、`vector_store` 存在。Ollama 用 `docker compose -p ai-java-cold-20261021 exec -T ollama ollama list` 核对 `nomic-embed-text`。
5. 在当前终端自行设置 `DEMO_USERNAME` 和 `DEMO_PASSWORD`，用测试邮箱运行 `python tools/demo-user-flow.py --email test@example.com`，验证从注册到续聊。检查文档 READY、评价分数存在、学习计划 ID 存在。保存结果文件中的 ID；凭证只保留在当前终端。
6. 对同一隔离项目执行 `docker compose -p ai-java-cold-20261021 restart`。用上一步的用户名和密码重新登录取得 JWT，再读取文档、下载原文件、执行 `/knowledge/search`，并核对 PostgreSQL 记录、RustFS 对象和向量仍在。
7. 保存脱敏后的命令退出码、服务状态和 API 结果。任何步骤失败时记录具体服务日志与失败阶段。测试结束可运行 `docker compose -p ai-java-cold-20261021 down`；此命令保留命名卷。只在明确确认项目名和卷名后，才考虑清理测试卷。

Compose 默认绑定本地 18203 端口。若已有服务占用，在 `.env` 中设置未占用的 `DEMO_PORT`，并把演示脚本的 `--url` 改成相应端口。Compose 定义只提供本地演示，不包含生产级备份、外网 TLS 或真实 SMTP 服务。
