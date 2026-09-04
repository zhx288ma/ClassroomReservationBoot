# Agent Semantic Retrieval and Tool Evaluation

## 1. Configure the real embedding provider

Rotate any key that was pasted into chat before using this setup. Do not save a key in Git, Java source, `application.yml`, screenshots, or a resume.

The Model Studio OpenAI-compatible endpoint requires the Beijing business workspace ID. Replace `YOUR_WORKSPACE_ID` below with the ID from Model Studio, not an API key.

```powershell
$env:CLASSROOM_AGENT_EMBEDDING_ENABLED = "true"
$env:CLASSROOM_AGENT_EMBEDDING_API_KEY = "rotated-model-studio-key"
$env:CLASSROOM_AGENT_EMBEDDING_BASE_URL = "https://YOUR_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
$env:CLASSROOM_AGENT_EMBEDDING_MODEL = "qwen3.7-text-embedding"
$env:CLASSROOM_AGENT_EMBEDDING_DIMENSIONS = "1024"
$env:CLASSROOM_AGENT_VECTOR_ENABLED = "true"
$env:CLASSROOM_QDRANT_URL = "http://localhost:6333"
```

Start Qdrant and the application:

```powershell
docker compose up -d qdrant
mvn spring-boot:run
```

As an administrator, call `POST /agent/knowledge/rebuild` in Swagger. This re-extracts stored documents, chunks them, generates 1024-dimension embeddings, and upserts them into Qdrant. `GET /agent/knowledge/status` should show `vectorDatabase: Qdrant` and `embeddingEnabled: true`.

## 2. Retrieval evaluation

Call `GET /agent/evaluations/retrieval` as an administrator. The `campus-policy-rag-v1` set has six Chinese policy questions and expected source documents. It reports:

- `Recall@1`: expected source is the first result.
- `Recall@3`: expected source appears in the first three results.
- `MRR@3`: rank-sensitive average reciprocal rank.

Run once with embedding disabled as the lexical baseline, then enable embedding + Qdrant, rebuild, and run it again. Record both JSON outputs with the knowledge-base version and date. Do not claim an improvement until both runs have been completed.

### Expanded RAG corpus and RRF

As an administrator, call `POST /agent/evaluations/corpus/seed`. It adds 20 generated, project-specific policy documents prefixed with `[RAG-EVAL]`; it does not overwrite uploaded documents. Each document is embedded and written to Qdrant when semantic retrieval is enabled. The documents are marked `EVAL_DATASET` and are excluded from normal student Agent retrieval; only the evaluation endpoint includes them.

`GET /agent/evaluations/retrieval` then runs 20 paraphrased retrieval queries and reports `Recall@1`, `Recall@3`, and `MRR@3`. The endpoint restricts both lexical and Qdrant vector retrieval to `EVAL_DATASET`, so uploaded policies and long external PDFs cannot pollute the regression baseline. Production questions still retrieve from all active non-evaluation knowledge. Retrieval uses dual recall (local lexical candidates plus Qdrant vector candidates) and Reciprocal Rank Fusion. RRF merges ranks rather than adding unrelated lexical and cosine scores.

### Historical comparison on the generated v2 corpus (2026-08-26)

| Mode | Recall@1 | Recall@3 | MRR@3 |
| --- | ---: | ---: | ---: |
| Lexical only | 0.7000 | 0.9000 | 0.7917 |
| Qwen embedding + Qdrant + RRF | 0.7500 | 0.9500 | 0.8417 |

This comparison uses 20 generated policy documents and 20 paraphrased questions. It is suitable for regression testing and interview discussion, but it is not a production benchmark; expand with de-identified real policy and feedback documents plus human-labelled relevance judgments.

### Latest isolated regression run (2026-08-26)

After adding a long external policy PDF, the evaluator was corrected to filter both lexical and Qdrant candidates to `EVAL_DATASET`. This prevents production documents from changing the fixed regression result. With Qwen embedding (1024 dimensions), Qdrant and RRF enabled:

| Cases | Recall@1 | Recall@3 | MRR@3 | Known miss |
| ---: | ---: | ---: | ---: | --- |
| 20 | 0.9000 | 0.9500 | 0.9250 | `最早和最晚可以什么时候签到？` did not retrieve its expected evaluation document in Top 3 |

Use this only as an **offline regression-test result on a generated 20-case corpus**. It is appropriate to state that the project has a measurable retrieval evaluation, but it is not evidence of general RAG quality across all university rules.

### Recorded smoke evaluation (2026-08-26)

With the built-in three-document campus-policy knowledge base and six paraphrased questions:

| Retrieval mode | Recall@1 | Recall@3 | MRR@3 |
| --- | ---: | ---: | ---: |
| Lexical fallback | 0.8333 | 1.0000 | 0.8889 |
| Qwen embedding + Qdrant (1024 dimensions) | 0.8333 | 1.0000 | 0.9167 |

This is only a smoke evaluation: the corpus and evaluation set are deliberately small. Expand with versioned external PDFs and human-labeled expected chunks before using it as a production-quality claim.

## 3. LangChain4j tool calling

The model is limited to three read-only tools: `searchOpenSlots`, `retrievePolicyKnowledge`, and `getMyReservations`. It has no reservation, cancellation, check-in, stock, or administrator-write tool.

```powershell
$env:CLASSROOM_AGENT_LANGCHAIN_ENABLED = "true"
$env:CLASSROOM_AGENT_LANGCHAIN_API_KEY = "rotated-deepseek-key"
$env:CLASSROOM_AGENT_LANGCHAIN_BASE_URL = "https://api.deepseek.com/v1"
$env:CLASSROOM_AGENT_LANGCHAIN_MODEL = "deepseek-chat"
```

Restart the application. Ask a rule question such as `签到窗口是什么，候补如何补位？`. The response mode should become `LANGCHAIN4J_TOOL_CALLING_RAG`; inspect `GET /agent/traces` for the invocation trace. If the provider is unavailable, the application automatically retains local RAG fallback and no reservation behavior changes.

## 4. Agent operational metrics

Call `GET /agent/evaluations/agent-metrics` as an administrator. It summarizes the latest 200 trace records: sample size, successful invocation rate, average duration, and runtime mode distribution. This is operational telemetry, not a claim of answer correctness; combine it with the retrieval evaluation above.

Call `POST /agent/evaluations/agent` for a versioned workflow evaluation. It measures completion rate, intent accuracy, expected-tool recall, required-fact coverage for rule answers, no-write-tool rate, and end-to-end P50/P95/P99 latency. The final metric starts before intent classification, so model routing time is included. The suite also verifies that direct write requests are blocked by the local `SAFETY_GUARD` before the model can invoke a tool.

### Latest Agent workflow run (2026-08-26)

On six controlled cases (rule Q&A, waitlist Q&A, personal reservation lookup, administrator statistics, reservation-draft generation, and direct-write refusal), the run achieved `100%` completion, intent accuracy, expected-tool recall, no-write-tool rate, and required-fact coverage for the two rule cases. End-to-end latency was average `2169ms`, P50 `815ms`, P95/P99 `5844ms`; the long tail is dominated by two external LLM-backed RAG answers. This is a small functional safety evaluation, not an LLM benchmark.
