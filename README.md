# Ai-Java-Interview

面向 Java 后端面试准备的服务端项目。用户可以注册、上传自己的知识文档、进行 AI 模拟面试，并依据面试记录生成学习计划。项目重点是让知识检索、Agent 引用、异步索引和持久化结果可追溯。

## 技术组成

Java 21、Spring Boot 3.5、Spring AI、MyBatis、PostgreSQL 17 与 pgvector、Redis Streams、RustFS、Ollama `nomic-embed-text`、DeepSeek。PostgreSQL 存业务记录和向量，RustFS 存原始文件，Redis Streams 驱动文档索引任务。应用以 JWT 区分用户。

架构图和关键流程见 [系统架构](docs/architecture/system.md)。详细 API 可从 `src/main/java/com/shtbs/interview/controller` 核对。

## 从零运行

需要 Docker Desktop 的 Linux 容器引擎、Java 21、Maven、可用的 DeepSeek API Key，以及用于注册验证码的 QQ SMTP 授权码。先复制 `.env.example` 为 `.env`，填写随机测试密码和真实密钥；将 `QQ_MAIL_AUTH_CODE` 写入本地 `.env`。`.env` 已被 Git 忽略。邮件发件人配置在 `src/main/resources/application.yml`，测试邮箱应能收到它发出的验证码。

```powershell
mvn clean verify
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

服务默认只绑定 `127.0.0.1:18203`。首次启动会下载 `nomic-embed-text`，时间取决于网络。Compose 的 PostgreSQL 空卷会按 `db/postgresql/001` 至 `006` 顺序初始化；应用启动时由 Spring AI 初始化 pgvector 向量表。不要在原开发数据卷上执行 `docker compose down -v`。隔离冷启动和重启核对步骤见 [部署验收](docs/testing/cold-start.md)。

本仓库此前完成的 [V8 稳定化报告](docs/testing/v8-stabilization-report-2026-10-20.md)证明了独立应用容器及部分真实链路；完整 Compose 空卷冷启动尚待实际执行，不能把配置检查当作验收通过。

## 运行演示

在可接收验证码的测试邮箱上运行：

```powershell
python tools/demo-user-flow.py --email test@example.com
```

脚本依次发送验证码、注册、登录、创建知识库、上传 Markdown、等待索引、开始模拟面试、保存评价、生成学习计划并续聊。收到验证码后在终端输入；也可通过 `DEMO_MAIL_CODE` 环境变量由外部测试邮件适配器提供。脚本使用新用户和新知识库，不写出 JWT、密码、邮箱或验证码。输出仅保留测试对象 ID。该脚本是可运行的验收入口；在本机 Docker 服务不可用时不应声称已通过。

## RAG 质量基线

运行中的应用可用 [固定评测脚本](tools/evaluate-rag.py)建立真实基线：30 篇合成 Java/后端知识文档、60 个有答案问题、5 个无答案问题。先用普通测试用户登录取得 JWT，并只在当前终端设置 `EVAL_TOKEN`：

```powershell
python tools/evaluate-rag.py --dry-run
python tools/evaluate-rag.py --output rag-evaluation-result.json
```

脚本新建隔离知识库，逐篇上传并等待发布，然后测 Recall@5、Agent 引用来源正确率和无答案误引率。引用来源正确率只是自动来源代理指标；“引用 Chunk 是否真的支持结论”需要按 [评测说明](docs/testing/rag-evaluation.md)人工复核。不要把未运行的评测脚本当作测得的性能数字。

## 构建与工程证据

`mvn clean verify` 执行默认测试并生成 `target/Ai-Java-Interview-1.0-SNAPSHOT.jar`。数据库和真实模型相关测试需要独立依赖与环境变量；未启用的测试会跳过。历史 V8 报告中 215 个唯一用例、6/6 固定检索和 14/14 真实 HTTP 验收是不同测试层次，不能相加。证据和限制见 `docs/testing/`。

值得讨论的工程问题包括：文档 V1/V2 发布切换时如何阻止旧引用提交；Redis Stream 任务失败后如何靠数据库状态重试；Agent 如何在保存面试评价前核对结构化事实；摘要更正如何回溯原始用户消息。实现细节见 [架构文档](docs/architecture/system.md)和历史测试报告。

## 面试展示建议

演示注册到学习计划的业务路径，再展示一次资料不足回答、一次旧版本引用被拒绝的测试证据。3–5 分钟项目介绍可按“用户问题 → 架构 → 关键难点 → 测试证据 → 当前边界”展开；不要把合成评测或尚未执行的冷启动写成生产指标。
