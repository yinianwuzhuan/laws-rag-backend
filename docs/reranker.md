# Cross-Encoder Reranker

项目使用两阶段检索：Qdrant 负责候选召回，Reranker 负责相关性重排。通过 `RERANKER_PROVIDER` 可以选择阿里云百炼 `qwen3-rerank` 或本地 ONNX 模型。

## 1. 阿里云百炼 qwen3-rerank

小内存生产服务器推荐使用云端重排：

```dotenv
RERANKER_ENABLED=true
RERANKER_PROVIDER=dashscope
RERANKER_APPLY_BY_DEFAULT=true
RERANKER_DASHSCOPE_BASE_URL=https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com
RERANKER_DASHSCOPE_PATH=/compatible-api/v1/reranks
RERANKER_DASHSCOPE_MODEL=qwen3-rerank
RERANKER_DASHSCOPE_INSTRUCT=Given a legal research query, retrieve relevant legal provisions that answer the query.
RERANKER_TIMEOUT_SECONDS=15
RERANKER_MAX_ATTEMPTS=2
RERANKER_INITIAL_BACKOFF_MS=300
RERANKER_FALLBACK_ENABLED=true
RERANKER_LOG_DETAILS=false
```

云端重排复用 `AI_DASHSCOPE_API_KEY`。调用失败或超时时，默认保留 Hybrid 融合顺序继续回答。使用该 Provider 时不会创建 ONNX Reranker Bean，也不需要挂载模型目录。

## 2. 准备本地 ONNX 模型

在具有 Python 3.10+ 的机器上执行一次：

```bash
python -m venv .venv-reranker
source .venv-reranker/bin/activate
pip install --upgrade pip
pip install "optimum[onnxruntime]" transformers torch sentencepiece hf_xet
optimum-cli export onnx \
  --model BAAI/bge-reranker-v2-m3 \
  --task text-classification \
  models/reranker
```

Windows PowerShell 激活命令：

```powershell
.venv-reranker\Scripts\Activate.ps1
```

项目也提供了一键导出脚本：

```powershell
.\scripts\export-reranker-onnx.ps1 -PythonExecutable "你的Python 3.10+路径"
```

导出后至少检查：

```text
models/reranker/
├── model.onnx
├── tokenizer.json
├── tokenizer_config.json
├── special_tokens_map.json
└── config.json
```

模型较大时还可能生成 ONNX external data 文件，必须连同整个目录一起复制，不能只复制 `model.onnx`。

## 3. 本地启用

设置环境变量后启动 Spring Boot：

```powershell
$env:RERANKER_ENABLED="true"
$env:RERANKER_PROVIDER="onnx"
$env:RERANKER_MODEL_PATH="C:\Java\jproject\laws-rag-backend\models\reranker"
mvn spring-boot:run
```

`RERANKER_ENABLED=true` 只负责加载模型并开放评测页开关。实际聊天默认仍保持纯向量。如需让聊天 RAG 默认使用重排，再设置：

```powershell
$env:RERANKER_APPLY_BY_DEFAULT="true"
```

## 4. ONNX服务器部署

模型放在服务器持久化目录：

```bash
mkdir -p /opt/laws-rag/models/bge-reranker-v2-m3
```

Docker Compose 的 backend 服务增加：

```yaml
services:
  backend:
    volumes:
      - /opt/laws-rag/models/bge-reranker-v2-m3:/app/models/reranker:ro
    environment:
      RERANKER_ENABLED: "true"
      RERANKER_PROVIDER: "onnx"
      RERANKER_APPLY_BY_DEFAULT: "false"
      RERANKER_MODEL_PATH: /app/models/reranker
      RERANKER_LOG_DETAILS: "false"
```

先保持 `RERANKER_APPLY_BY_DEFAULT=false`，在评测页使用 Dev 集对比：

```text
纯向量：最终 TopK=5
重排：候选 TopK=10，最终 TopK=5
```

方案确认后再把聊天默认开关改成 `true`。

## 5. 真实模型自检

模型准备完成后可手工运行：

```powershell
$env:RUN_RERANKER_MODEL_TEST="true"
$env:RERANKER_MODEL_PATH=(Resolve-Path models/reranker).Path
mvn -q "-Dtest=OnnxCrossEncoderRerankerModelTest" test
```

测试会验证与“未签书面劳动合同”相关的劳动合同法条能够从无关刑法条文之后提升到第一名。

## 6. 结果字段

检索结果同时保留：

- `vector_rank` / `vector_score`：Qdrant 原始排名与分数。
- `rerank_rank` / `rerank_score`：Cross-Encoder 重排排名与归一化分数。
- `rerank_raw_score`：模型原始 logit，仅用于诊断。

归一化分数用于展示，不应解释为法律结论正确概率。

学习阶段可以设置 `RERANKER_LOG_DETAILS=true`。后台将逐题打印用户问题、向量候选全文、向量排名与分数，以及重排后的完整顺序和重排分数。日志可能包含用户问题并明显增加体积，生产环境应设为 `false`。
