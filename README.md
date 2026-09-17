# Laws Agent · 法律智能咨询后端

基于 Java 21、Spring Boot、Spring AI 和 Qdrant 的法律 RAG 实践项目。系统将法律 Markdown 文档解析入库，结合 Dense 语义检索、BM25 关键词检索、RRF 融合和 Reranker 生成法律咨询答复；对复杂问题，还会拆分子问题、检查证据缺口，并在必要时补查官方网页。 

> **在线体验：** [https://laws-agent.ccwu.cc](https://laws-agent.ccwu.cc)  
> **前端仓库：** [laws-rag-front](https://github.com/yinianwuzhuan/laws-rag-front)  
> **后端仓库：** [laws-rag-backend](https://github.com/yinianwuzhuan/laws-rag-backend)（本仓库）

前后端是两个独立 Git 仓库：本仓库提供文档入库、检索、Agent 问答与评测 API；前端仓库提供法律咨询、文件上传和评测工作台。两者通过 `/api` 接口连接，可以分别开发和部署。

## 功能概览

- **法律文档入库：**解析标准法条及官方 Markdown 的元数据、序言、附件和无条号正文；支持单文件预览、上传、批量上传与目录导入。使用稳定业务 ID 和 `source_name + content_hash` 去重，重复执行不会重复向量化。
- **多阶段检索：**Dense、BM25 或 Hybrid（Qdrant RRF）召回；支持问题重写、法律名称与条号提示、候选重排，以及检索前后排名展示。
- **法律咨询：**多轮会话路由、复杂问题拆解、分项检索、初稿生成、完整性与矛盾检查、针对缺口补查及最终答复。内部依据不足时，可搜索白名单内的官方法律网站，并抓取正文核验。
- **可复现评测：**提供版本化黄金问题集，按 Dev/Test 运行检索评测；展示 Hit、Recall、Precision、MRR、NDCG 和延迟。生成评测分别使用黄金法条与真实检索结果生成答案，再由独立 Judge 模型评价。
- **可观测性：**记录问题重写、Embedding API、Qdrant、重排和生成等阶段的耗时；生成评测记录模型返回的 Token 用量。

这是学习与展示项目，**不是律师服务，也不保证回答适用于具体案件**。法律规则、效力状态、地方标准及个案事实应以最新权威来源和专业意见为准。

## 技术栈与链路

| 层次 | 实现 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3、Spring AI、SSE |
| 向量与关键词检索 | Qdrant Dense 向量、BM25 稀疏向量、Qdrant RRF |
| 模型 | OpenRouter Embedding、通义聊天模型；远程 DashScope 或本地 ONNX Reranker |
| 官方补查 | 搜索候选 URL，仅抓取并校验允许域名的官方网页正文 |
| 构建部署 | Maven、Docker、GitHub Actions、GHCR |

```text
Markdown 法律文档 → 结构化解析与去重 → Embedding + BM25 → Qdrant

用户问题 → 多轮路由 / 子问题规划
         → Dense + BM25 候选 → RRF 融合 → Reranker
         → 证据完整性检查 → 必要时内部补查 / 官方网页补查
         → 法律答复（SSE 流式输出）
```

默认检索配置为候选 Top 10、最终 Top 5；这些值可在 [`application.yaml`](src/main/resources/application.yaml) 中调整。Reranker 可选择远程 DashScope 或本地 ONNX，使用本地模式时需自行准备模型文件，仓库不包含模型权重。

## 法律语料来源与处理

本项目的法律知识库并非由模型自动生成，主要由两路文档构成：

| 语料 | 来源与处理方式 |
| --- | --- |
| 基础法律 | 参考开源项目 [ImCa0/just-laws](https://github.com/ImCa0/just-laws) 整理的法律 Markdown 文档及其按编、章、节、条组织的结构，作为初期标准法条语料和解析规则的适配基础。感谢原项目作者；该部分不应被视为本项目原创采集。 |
| 扩展法律文件 | 从[国家法律法规数据库](https://flk.npc.gov.cn/search)检索并下载官方提供的 DOCX 文档，在本仓库之外完成 DOCX → Markdown 转换、清洗和结构化整理，再交由本后端解析入库。扩展范围包括行政法规、监察法规、司法解释、法律解释及有关决定等；实际入库范围以本地准备的语料和导入结果为准。 |

扩展语料的 Markdown 保留文档类型、官方文档 ID、官方分类、制定或发布机关、公布与施行日期、效力状态及原始页面 `sourceUrl` 等元数据，便于追溯来源与核查版本。后端将标题、正文、条号和相关元数据解析为检索单元，计算内容哈希去重，再生成 Dense/BM25 检索数据写入 Qdrant。同名法律存在多个来源时，目录导入优先选择带官方元数据的版本，并跳过导航文件和 `versions` 历史目录。

```text
Just Laws Markdown ───────────────────────────────┐
国家法律法规数据库 DOCX → 上游清洗与 Markdown 转换 ├→ 后端解析、去重与向量化 → Qdrant
                                                  ┘
```

DOCX 下载与转换流程、原始文件及完整语料库**不包含在本仓库**；克隆代码不会自动获得这些文档或已入库的 Qdrant 数据。使用 [Just Laws 原项目](https://github.com/ImCa0/just-laws) 的内容时，请遵守其 [MIT 许可](https://github.com/ImCa0/just-laws/blob/master/LICENSE)并保留要求的版权与许可声明。国家法律法规数据库在这里是文档来源，**不代表其对本项目的授权或背书**；引用、再分发文档时应核对相应来源的使用要求，法律效力和现行文本应以官方最新公布内容为准。

## 本地运行

### 1. 准备环境

- JDK 21、Maven、Docker（或独立运行的 Qdrant）。
- 可用的通义聊天模型与 OpenRouter Embedding 凭据；若运行生成评测，还需要单独配置 Judge 服务。
- Qdrant gRPC 端口默认为 `6334`，HTTP 管理端口为 `6333`。例如使用持久化 Docker 卷启动：

```bash
docker run -d --name laws-rag-qdrant \
  -p 6333:6333 -p 6334:6334 \
  -v laws-rag-qdrant-data:/qdrant/storage \
  qdrant/qdrant
```

### 2. 配置环境变量并启动

不要将真实密钥写入仓库。以下是 **PowerShell 示例**；在 Linux/macOS 上可用相应的 `export` 命令设置同名变量。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:AI_DASHSCOPE_API_KEY = '<你的通义密钥>'
$env:AI_DASHSCOPE_CHAT_BASE_URL = '<与你的通义模型匹配的接口根地址>'
$env:OPENROUTER_API_KEY = '<你的 OpenRouter 密钥>'
$env:QDRANT_HOST = 'localhost'
mvn spring-boot:run
```

后端默认监听 `http://localhost:8081`。聊天模型的接口地址、路径和模型名须与你实际开通的服务保持一致；不要直接照搬仓库中的默认工作空间地址。若使用远程重排，还需配置可用的 DashScope 重排接口及密钥；可通过 `RERANKER_ENABLED=false` 暂时关闭重排。生产环境的完整配置项见 [`application-prod.yaml`](src/main/resources/application-prod.yaml) 和 [`application.yaml`](src/main/resources/application.yaml)。

启动后可检查检索能力：

```bash
curl http://localhost:8081/api/law/search/capabilities
```

Qdrant 服务启动成功不代表已有法律数据。你需要上传自己的 Markdown 文档，或按下一节导入语料。**官方 DOCX 到 Markdown 的采集与转换属于上游数据准备流程，不在本仓库内；本仓库负责 Markdown 的解析和入库。**

## 导入法律语料

前端支持拖拽批量上传 `.md`/`.markdown` 文件，也可以通过后端接口上传。目录级导入使用受环境变量保护的 [`FullLawCorpusImportTest`](src/test/java/org/example/lawsrag/FullLawCorpusImportTest.java)，先预检冲突，再实际入库：

```powershell
$env:RUN_FULL_LAW_INGEST = 'true'
$env:FULL_LAW_DOCS_PATH = 'D:/your-law-corpus/docs'
$env:FULL_LAW_DRY_RUN = 'true'
mvn '-Dtest=FullLawCorpusImportTest' test

# 确认扫描结果及冲突数后，再执行真实入库
$env:FULL_LAW_DRY_RUN = 'false'
mvn '-Dtest=FullLawCorpusImportTest' test
```

目录导入会跳过导航文件与 `versions` 等历史目录；同名法律优先采用带官方元数据的版本。正式执行会调用付费 Embedding API 并写入 Qdrant，先用小样本和 dry-run 验证。仓库中的黄金评测集不等于完整法律语料，也不会自动填充数据库。

## 主要 API

| API | 作用 |
| --- | --- |
| `GET /api/law/source-types` | 查询上传文档支持的来源类型 |
| `POST /api/law/preview` | 预览 Markdown 解析结果，不入库 |
| `POST /api/law/upload`、`POST /api/law/upload/batch` | 单文件或批量入库 |
| `GET /api/law/search/capabilities` | 查询可用召回、重排与重写能力 |
| `GET /api/law/search` | 自由检索；可指定 Dense/BM25/Hybrid、TopK、重排、重写及元数据过滤 |
| `POST /api/chat/law/stream` | 流式法律咨询与 Agent 过程事件 |
| `GET /api/evaluation/retrieval/stream` | 逐题检索评测 SSE |
| `GET /api/evaluation/generation/stream` | 双路生成评测 SSE |

接口参数以各 Controller 实现为准。评测数据说明见 [`eval/README.md`](eval/README.md)；调整检索参数时优先使用 Dev，方案确定后再运行 Test。评测结果依赖语料版本、模型和服务状态，历史分数不代表所有法律问题的准确率。

## 部署与安全

本仓库的 [GitHub Actions 工作流](.github/workflows/deploy.yml) 在推送 `master` 后构建镜像并推送至 GHCR，再通过 SSH 登录服务器执行 `docker compose pull backend` 与 `docker compose up -d --no-deps backend`。服务器上的 Compose 文件、运行时环境变量、数据卷及 GitHub Actions Secrets 需要自行配置；本仓库不包含可直接复制的生产 Compose 文件。

- 不提交 API Key、SSH 私钥、`.env`、`application-local.yaml`、Qdrant 数据或模型权重；历史上泄露过的密钥必须轮换。
- 使用自己的构建环境时，确认本地配置文件不会被打包进 JAR 或镜像。发布前检查构建产物，而不仅是 `git status`。
- 面向公网部署前，应为上传、评测等高成本接口增加认证、限流与配额；不要将学习项目直接视作可安全公开的生产 API。
- 法律问题可能包含个人敏感信息。生产环境应限制日志访问，关闭详细 Judge/Reranker 输入输出日志，并制定数据保留策略。
- 若计划允许第三方复制、修改或再分发代码，请另行添加明确的 `LICENSE` 文件；公开仓库本身不等于授予开源许可证。

## 相关项目

- [laws-rag-front：Vue 3 前端与评测工作台](https://github.com/yinianwuzhuan/laws-rag-front)
- [在线体验：laws-agent.ccwu.cc](https://laws-agent.ccwu.cc)
