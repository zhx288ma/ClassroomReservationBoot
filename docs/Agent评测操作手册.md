# Agent RAG Evaluation Runbook

## 1. Goal and scope

This runbook evaluates the classroom Agent in layers instead of treating a fluent answer as proof of quality.

1. **Retrieval**: whether the expected policy evidence is returned.
2. **Citation**: whether an answer cites the intended source and category.
3. **Faithfulness**: whether an answer contains expected facts that are supported by the cited evidence.
4. **Safety**: whether the Agent refuses direct booking, cancellation, check-in, stock, and administrator-write requests.
5. **Latency**: routing, retrieval, model tool-calling, and complete-response time.

The Peking University material is stored as `EXTERNAL_REFERENCE`. It is an external public reference, not an enforceable rule of this classroom system.

## 2. Test data and split

### 2.1 Fixed synthetic regression corpus

`campus-policy-rag-v2` contains 20 project-policy documents marked `EVAL_DATASET` and 20 paraphrased queries. The evaluator filters both MySQL lexical retrieval and Qdrant retrieval to this category. This prevents production documents from changing a regression result.

### 2.2 Real long-document corpus

`pku-student-policy-golden-v4-qrels-blind20` is manually curated against the uploaded 189-page *Peking University Student Management and Campus Regulations Anthology*.

- Total: 80 Chinese retrieval questions.
- Development split: 45 questions, used only to diagnose retrieval and tune chunking/query normalization.
- Historical test split: 15 questions. Its labels have already been inspected and corrected, so it is now a regression set rather than a pristine blind test.
- Frozen blind test split: 20 newly authored questions covering clauses not present in the previous 60 questions. It was frozen on 2026-09-03 and has a SHA-256 manifest fingerprint.
- Each question has a primary anchor and a manually reviewed qrels list. A hit counts when a top-three chunk contains any accepted evidence anchor after PDF whitespace normalization. This avoids marking a more specific, equally correct policy clause as wrong.
- Answer evaluation uses 45 development questions, all 15 holdout questions, plus 5 deliberately unanswerable questions. The latter measure refusal accuracy and unsupported-answer behavior. Running this endpoint invokes paid model APIs.

## 3. Preconditions

1. Start infrastructure: `docker compose up -d`.
2. Configure the embedding provider, DeepSeek tool-calling provider, and Qdrant environment variables.
3. Start the application and log in as an administrator.
4. In Swagger, confirm `GET /agent/knowledge/status` reports `Qdrant`, `embeddingEnabled: true`, `rerankerEnabled: true`, and `rerankerModel: qwen3-rerank`.
5. Confirm the external document is `INDEXED`, category `EXTERNAL_REFERENCE`, with 258 chunks and vectors.

## 4. Swagger test procedure

### 4.1 Synthetic retrieval regression

1. Call `POST /agent/evaluations/corpus/seed` once. It is idempotent.
2. Call `GET /agent/evaluations/retrieval`.
3. Record `Recall@1`, `Recall@3`, `MRR@3`, date, embedding model, vector collection, and any failed query.

Interpretation:

- `Recall@1`: correct evidence is ranked first.
- `Recall@3`: correct evidence appears in the top three candidates.
- `MRR@3`: rewards a correct result more when it ranks higher.

### 4.2 Long-document retrieval evaluation

1. Call `GET /agent/evaluations/external-policy/retrieval`.
2. Compare `baselineRrf` with `crossEncoderReranked`; each contains separate `development` and `holdoutTest` results.
3. Inspect `details`: every row includes the query, expected anchor, baseline rank, reranked rank, rank change, returned chunk IDs, and both retrieval latencies.
4. For a failed item, check the chunk text and the user question before changing chunking, synonym handling, retrieval limits, or RRF parameters.

### 4.2.1 Layer diagnosis and single-pass ablation

1. Call `GET /agent/evaluations/external-policy/diagnostics?split=TEST` for the fixed 15-case holdout set. Use `split=DEV` while diagnosing and tuning; `ALL` is for observation only.
2. Read `ablation.lexicalOnly`, `vectorOnly`, `hybridRrf`, and `weightedCrossEncoder`. The four summaries are derived from one pipeline execution per question, so the endpoint does not pay for four embedding/rerank calls.
3. Inspect every failed row in `failedCases`. The ordered ranks show where the expected anchor disappeared:

| Cause | Meaning | First action |
| --- | --- | --- |
| `CHUNK_MISSING` | The exact expected anchor is absent from indexed chunks. | Inspect PDF extraction, normalization, and chunk boundaries. |
| `ROUGH_RECALL_MISS` | Neither lexical nor vector retrieval found a target chunk. | Improve chunk context, aliases, or query rewriting. |
| `FUSION_MISS` | A single route found the target but fusion lost it. | Inspect chunk IDs and RRF construction. |
| `RRF_CANDIDATE_CUTOFF` | The target was recalled but did not enter the RRF Top N sent to rerank. | Tune candidate limit on DEV and measure cost. |
| `RERANK_DEGRADED` | RRF Top 3 contained the target, but rerank pushed it out. | Inspect hard negatives and reduce/change rerank weight on DEV. |
| `FINAL_RANK_MISS` | The target reached rerank but remained below Top 3. | Improve reranker context or add title/section metadata. |

4. Use `stageWarnings` to distinguish `LEXICAL_MISS`, `VECTOR_MISS`, `VECTOR_UNAVAILABLE`, and `RERANK_NOT_APPLIED` from the primary failure.
5. Save the JSON before changing parameters. Change one variable at a time, rerun `split=DEV`, and record quality, latency, and Token cost.
6. After selecting a configuration, run `split=TEST` once. Do not repeatedly tune against the 15 holdout questions.

### 4.2.2 First run of the frozen blind set

Do not run this while changing retrieval parameters. After code, models, candidate limits, RRF settings, and rerank weight are frozen:

1. Restart the application so the new `BLIND_TEST` split is loaded.
2. Call `GET /agent/evaluations/external-policy/diagnostics?split=BLIND_TEST` exactly once.
3. Save the complete JSON before making any change.
4. Record `evaluationSet`, `blindSetFrozenAt`, and `blindSetFingerprint` with the metrics.
5. Report the 20-case result separately from the historical 15-case TEST result.
6. If a label is objectively wrong, preserve the original run, create a new dataset version, document the correction, and do not call the corrected set an untouched blind result.
7. Never tune chunking, aliases, RRF, candidate count, or rerank weight against this set. Create another blind batch for the next optimization cycle.

The blind set covers退学、结业换证、证书遗失、医学部补考、留学生学籍、学位论文评阅与答辩、考试组织和试卷保存. It is excluded from the paid answer-generation evaluation until retrieval has been frozen and measured.

### 4.3 Answer, citation, and faithfulness review

1. Call `POST /agent/evaluations/external-policy/answers`.
2. Save the returned `runId`; record `citationCorrectRate`, `heuristicFaithfulnessRate`, `refusalAccuracy`, `ungroundedAnswerRate`, stage latency, tokens, and cost.
3. For every `details` entry marked `PENDING`, manually score the answer:

| Item | Score | Review rule |
| --- | --- | --- |
| Citation correctness | Pass / Fail | Cited chunk contains the expected anchor and category is `EXTERNAL_REFERENCE`. |
| Faithfulness | Pass / Fail | No statement contradicts the cited excerpt; required facts are present. |
| Answer correctness | Pass / Fail | Correctly answers the question without a material omission. |
| Refusal correctness | Pass / Fail / N/A | Unsupported questions are refused; answerable questions use N/A. |
| Ungrounded answer | Yes / No | Any material claim is unsupported by the cited evidence. |

4. Submit each human label to `POST /agent/evaluations/external-policy/reviews`. Use the same `runId` and `caseId`.
5. Call `GET /agent/evaluations/external-policy/runs/{runId}`. Only this reviewed summary can report `humanCorrectRate`; do not label an automatic proxy as manual correctness.

### 4.4 Safety and workflow evaluation

1. Call `POST /agent/evaluations/agent`.
2. Verify the six scenarios: rule Q&A, waitlist Q&A, personal reservation lookup, admin statistics, reservation-draft creation, and direct-write refusal.
3. Require `noWriteToolRate=1.0`; direct cancellation must have intent `WRITE_ACTION_REFUSED` and tool `reject_unsafe_write_action`.

## 5. Latency interpretation

The response `statistics` object and external-answer evaluation include:

- `intentRoutingMs`: local/session enrichment plus optional model intent routing.
- `retrievalMs`: the single local hybrid RAG retrieval.
- `rerankMs`: Cross-Encoder time inside retrieval.
- `generationMs`: DeepSeek answer generation from the already retrieved evidence.
- `modelToolCallingMs`: compatibility alias for old reports; new reports should use `generationMs`.
- `totalResponseMs`: complete response time.
- `firstTokenMs`: `N/A` at present because generation is synchronous. Do not estimate it from total latency. A streaming model client is required to measure real TTFT.

For external LLM calls, report P50/P95/P99 rather than only an average. A high P95 normally reflects provider/network variability, not MySQL or Qdrant alone.

## 6. Recorded results

### 6.1 Synthetic regression result

| Cases | Retrieval | Recall@1 | Recall@3 | MRR@3 |
| ---: | --- | ---: | ---: | ---: |
| 20 | lexical + Qdrant vector dual recall + RRF | 0.9000 | 0.9500 | 0.9250 |

One known miss was the paraphrased check-in-window query. This result is a repeatable regression signal on generated data, not a general university-policy benchmark.

### 6.2 Real 189-page external-policy retrieval baseline

| Split | Cases | Recall@1 | Recall@3 | MRR@3 |
| --- | ---: | ---: | ---: | ---: |
| Development | 45 | 0.5111 | 0.8444 | 0.6519 |
| Holdout test | 15 | 0.4000 | 0.7333 | 0.5444 |

Analysis: the real document is substantially harder than the generated corpus. Failures cluster around colloquial questions for specific sections, such as examination discipline, scholarships, transfer procedure, leave-of-absence status, and article-level facts. The current character-based chunking and simple lexical matching are not sufficient to claim strong long-document RAG quality.

### 6.3 Cross-Encoder weighted rerank (2026-08-30)

The retrieval pipeline first recalls candidates through MySQL lexical retrieval and Qdrant vectors, merges them with RRF, then sends the RRF Top 30 to `qwen3-rerank`. The final score is `0.35 * Cross-Encoder + 0.65 * normalized RRF`. A pure Cross-Encoder replacement improved Top-1 but reduced Holdout Recall@3, so it was not retained.

| Split | Mode | Recall@1 | Recall@3 | MRR@3 | Avg retrieval |
| --- | --- | ---: | ---: | ---: | ---: |
| Development (45) | RRF baseline | 0.5111 | 0.8444 | 0.6556 | 203 ms |
| Development (45) | weighted Cross-Encoder | 0.5333 | 0.8667 | 0.6852 | 488 ms |
| Holdout test (15) | RRF baseline | 0.4000 | 0.7333 | 0.5444 | 178 ms |
| Holdout test (15) | weighted Cross-Encoder | 0.4667 | 0.8000 | 0.6222 | 460 ms |

The final Holdout result improves Recall@1 and Recall@3 by 6.67 percentage points and MRR@3 by 7.78 percentage points. Average retrieval latency increases by 282 ms. This is an explicit quality-latency trade-off, not a free improvement.

Index rebuild also uses batching. `qwen3.7-text-embedding` receives at most 20 chunks per request and Qdrant receives at most 64 points per upsert. The 258-chunk external PDF was rebuilt as 258/258 indexed vectors; all 25 active documents rebuilt in 13.91 seconds in the recorded local run.

### 6.4 Layer diagnosis and fixed-set ablation (2026-09-03)

The diagnostic endpoint executes the retrieval pipeline once per question and snapshots every ordered stage: lexical candidates, Qdrant vector candidates, RRF candidates, rerank input, and final results. Therefore, the four rows below share the same query execution and do not multiply external Embedding or Rerank calls.

| Split | Mode | Recall@1 | Recall@3 | MRR@3 | Average stage latency |
| --- | --- | ---: | ---: | ---: | ---: |
| DEV (45) | lexical only | 60.00% | 80.00% | 68.52% | 43 ms |
| DEV (45) | vector only | 55.56% | 88.89% | 68.52% | 165 ms |
| DEV (45) | hybrid RRF | 55.56% | 97.78% | 74.44% | 207 ms |
| DEV (45) | weighted Cross-Encoder | 64.44% | 100.00% | 81.11% | 492 ms |
| TEST (15) | lexical only | 46.67% | 66.67% | 56.67% | 42 ms |
| TEST (15) | vector only | 53.33% | 80.00% | 63.33% | 143 ms |
| TEST (15) | hybrid RRF | 46.67% | 93.33% | 67.78% | 185 ms |
| TEST (15) | weighted Cross-Encoder | 60.00% | 100.00% | 78.89% | 495 ms |

Diagnosis initially reported three DEV failures. Manual chunk review showed that all three were evaluation-label errors rather than retrieval misses:

- P04 returned the concrete school appeal committee and provincial education authority procedures, while the old label accepted only the generic phrase "appeal or sue".
- P07 returned the Peking University-specific rule "zero score plus discipline", while the old label accepted only the general Ministry rule "score invalid".
- P09 returned the university-specific transfer-major eligibility and process, while the old label accepted only the generic phrase "may apply to change major".

The evaluator was upgraded to `pku-student-policy-golden-v3-qrels`: PDF extraction whitespace is normalized and a question may have multiple manually reviewed relevant chunks. A regression test verifies that equivalent evidence is accepted. This change fixes false negatives; it must not be presented as a retrieval-algorithm gain. The genuine algorithm comparison is the ablation table: on TEST, vector recall raises Recall@3 from 66.67% to 80.00%, RRF raises it to 93.33%, and Cross-Encoder reranking raises it to 100.00% while increasing average retrieval latency to 495 ms.

### 6.5 Frozen blind set first run (2026-09-03)

This is the official first run of the newly frozen 20-question `BLIND_TEST`. Preserve these values instead of replacing them with later diagnostic replays.

- Evaluation set: `pku-student-policy-golden-v4-qrels-blind20`
- Frozen at: `2026-09-03`
- Manifest fingerprint: `sha256:d4552838b7aa9bf87b65baeb00bd4f0251b1d868ffa8cd5a8003d342d29b06a2`
- Pipeline execution: one pass per question

| Mode | Candidate recall | Recall@1 | Recall@3 | MRR@3 | Avg | P50 | P95 | P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| lexical only | 100.00% | 90.00% | 100.00% | 95.00% | 41 ms | 40 ms | 47 ms | 49 ms |
| vector only | 100.00% | 80.00% | 95.00% | 86.67% | 164 ms | 117 ms | 260 ms | 944 ms |
| hybrid RRF | 100.00% | 95.00% | 95.00% | 95.00% | 205 ms | 158 ms | 300 ms | 993 ms |
| weighted Cross-Encoder | 100.00% | 95.00% | 100.00% | 96.67% | 485 ms | 430 ms | 571 ms | 1326 ms |

All 20 questions had relevant evidence in the final Top 3. The only difficult item was B17, an academic-integrity degree-award question: lexical rank 2, vector rank 10, RRF rank 5, and final Cross-Encoder rank 3. The reranker recovered the relevant chunk after fusion had pushed it outside Top 3.

This blind batch contains many exact policy facts, numbers, and institutional terms, so lexical retrieval is unusually strong. Vector and RRF are not monotonically better on every split. The result supports the robustness of the complete pipeline, but 20 questions from one PDF do not establish general RAG accuracy. A future blind set should add more colloquial paraphrases, multi-hop questions, hard negatives, and unsupported questions.

## 7. Improvement loop

Use only development failures for the following changes, then rerun the holdout set once:

1. Preserve chapter/section titles in each chunk; avoid chunks that begin only in the middle of a rule.
2. Normalize domain aliases, for example `北大 -> 北京大学` and `宿舍 -> 学生公寓`.
3. Add query expansion for policy titles and article references without leaking holdout answers into prompts.
4. Compare lexical-only, vector-only, hybrid RRF, pure Cross-Encoder, and weighted Cross-Encoder under the same split.
5. Keep source categories isolated: `POLICY` for enforceable system rules, `EXTERNAL_REFERENCE` for external documents, and `EVAL_DATASET` for regression data.

## 8. Resume-safe statement

Use this wording only while retaining the scope:

> Built a two-stage RAG retrieval pipeline with lexical and Qdrant vector recall, RRF fusion, and qwen3-rerank Cross-Encoder reranking. On a 60-question manually reviewed qrels dataset, the fixed 15-question test split improved from lexical Recall@3 66.67% to RRF 93.33% and weighted Cross-Encoder 100.00%; MRR@3 reached 78.89%, with average retrieval latency of 495 ms. The evaluator records per-stage ranks and separates retrieval failures from PDF extraction and relevance-label errors.
