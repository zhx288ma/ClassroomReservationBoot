# Agent 单次检索、调用观测与答案质量评测指南

## 1. 本次改造解决的问题

旧规则问答会先在 `AgentServiceImpl` 检索一次，随后 LangChain4j Tool Calling 可能再次调用 `retrievePolicyKnowledge`。这会重复调用问题 Embedding、Qdrant 和 Cross-Encoder，不仅增加费用，还会放大响应时间。

新链路为：

```text
规则问题
  -> 一次 MySQL 关键词 + Qdrant 向量召回
  -> RRF 融合
  -> 一次 Cross-Encoder 精排
  -> 得到 Top 3 引用和完整 chunk 证据
  -> 将同一份 PRE_RETRIEVED_EVIDENCE 交给 DeepSeek
  -> LangChain4j ChatModel 返回答案和 TokenUsage
```

模型生成阶段不再暴露检索 Tool，因此不会发生第二次 Embedding 和 Rerank。教室搜索、个人预约等只读 Tool 仍保留，但预约、取消、签到和库存修改始终不开放给模型。

## 2. 每次 Agent 请求记录什么

`AgentCallMetricsContext` 在一个同步请求内汇总以下模型调用：

- `EMBEDDING`：问题或知识 chunk 向量化。
- `RERANK`：Cross-Encoder 对 query-document 候选精排。
- `CHAT_INTENT`：仅模糊请求才调用模型分类；明显规则、预约和统计请求优先本地路由。
- `CHAT_GENERATION`：基于已检索证据生成最终答案。

每条模型调用记录：provider、model、durationMs、inputTokens、outputTokens、totalTokens、tokenEstimated、estimatedCost 和 currency。

Embedding/Rerank 如果供应商返回 `usage` 就使用真实 Token；没有 `usage` 时按中英文字符长度估算并标记 `tokenEstimated=true`。DeepSeek 通过 LangChain4j `ChatResponse.tokenUsage()` 读取供应商返回值，缺失时同样使用估算值。

## 3. Trace 持久化字段

Flyway `V7__agent_observability_and_answer_evaluation.sql` 为 `tb_agent_trace` 增加：

- `model_names`、`model_calls_json`
- `retrieval_ms`、`rerank_ms`、`generation_ms`
- `input_tokens`、`output_tokens`、`total_tokens`
- `estimated_cost`、`cost_currency`

管理员可调用 `GET /agent/traces` 查看单次请求，调用 `GET /agent/evaluations/agent-metrics` 查看最近 200 条 Trace 的成功率、分阶段延迟、模型分布、Token 总量和费用总量。

## 4. 费用配置

费用单价不能硬编码，因为供应商、区域、免费额度和活动价格会变化。应从当前百炼和 DeepSeek 控制台取得单价后配置，单位为“人民币/百万 Token”：

```powershell
$env:CLASSROOM_AGENT_COST_CURRENCY = "CNY"
$env:CLASSROOM_AGENT_LLM_INPUT_PRICE_PER_MILLION = "控制台当前输入单价"
$env:CLASSROOM_AGENT_LLM_OUTPUT_PRICE_PER_MILLION = "控制台当前输出单价"
$env:CLASSROOM_AGENT_EMBEDDING_PRICE_PER_MILLION = "控制台当前Embedding单价"
$env:CLASSROOM_AGENT_RERANK_PRICE_PER_MILLION = "控制台当前Rerank单价"
```

计算公式：

```text
LLM费用 = 输入Token / 1,000,000 * 输入单价
        + 输出Token / 1,000,000 * 输出单价

Embedding/Rerank费用 = 总Token / 1,000,000 * 对应单价
```

单价保持为 0 时，接口返回 `costConfigured=false`、`estimatedCost=null`，不会用过期价格制造一个虚假的费用数字。

## 5. 三类质量指标不能混淆

### 5.1 检索指标

`Recall@3` 只检查期望锚点所在 chunk 是否进入 Top 3。它不判断最终答案是否引用、曲解或编造。

### 5.2 自动答案代理指标

- `citationCorrectRate`：答案包含 `[S1]` 等引用标记，并且引用源属于 `EXTERNAL_REFERENCE`，对应 chunk 含人工锚点。
- `heuristicFaithfulnessRate`：引用正确、预设关键事实同时出现在答案和证据中，并且句子级双字词证据支持率不少于 50%。这是启发式代理，不是人工忠实度。
- `refusalAccuracy`：5 条知识库无答案的问题中，模型是否明确表示“知识库暂无足够依据”等拒答语义。
- `ungroundedAnswerRate`：可回答问题缺少正确引用或证据支持率不足，以及不可回答问题仍强行回答的比例，越低越好。
- `evidenceSupportRate`：把回答拆成陈述句，计算每句双字词与证据文本的重合情况。免责声明不参与计算。

### 5.3 人工指标

人工复核员阅读问题、完整答案和引用 chunk 后独立填写：

- `citationCorrect`
- `faithful`
- `correct`
- `refusalCorrect`，仅拒答用例需要
- `ungrounded`
- `comment`

只有填写后的数据才进入 `humanCorrectRate`、`humanFaithfulnessRate`、`humanCitationCorrectRate`、`humanRefusalAccuracy` 和 `humanUngroundedAnswerRate`。

## 6. Swagger 完整测试步骤

1. 重启应用，让 Flyway 执行 V7。
2. 管理员登录并在 Swagger Authorize 中填入 JWT。
3. 调用 `GET /agent/knowledge/status`，确认 Embedding、Qdrant 和 Rerank 已启用。
4. 先调用 `GET /agent/evaluations/external-policy/retrieval`，记录 45 DEV、15 TEST 的 Recall@1、Recall@3、MRR@3。
5. 确认账户余额后调用一次 `POST /agent/evaluations/external-policy/answers`。该接口运行 45 条 DEV、15 条 HOLDOUT 答案用例和 5 条拒答用例，会产生外部模型费用。
6. 保存返回的 `runId`，逐条检查 `details`。
7. 对每条结果调用 `POST /agent/evaluations/external-policy/reviews`：

```json
{
  "runId": "answer-eval-xxx",
  "caseId": "P46",
  "citationCorrect": true,
  "faithful": true,
  "correct": true,
  "refusalCorrect": null,
  "ungrounded": false,
  "comment": "结论与引用条款一致，且明确说明外部资料边界"
}
```

拒答用例把 `citationCorrect` 设为 `null`，填写 `refusalCorrect`。

8. 调用 `GET /agent/evaluations/external-policy/runs/{runId}`。`manualMetrics.status=COMPLETED` 后，才可以把人工正确率写进报告。

## 7. 报告口径

在尚未重新运行付费答案评测前，只能陈述代码能力，不能编造答案质量结果。可保留已复现的检索结果：固定 15 条 TEST 的 chunk-level Recall@3 为 80%，MRR@3 为 62.22%。新的引用正确率、忠实度、拒答准确率、无依据回答率、Token 和费用必须以新 `runId` 的实际输出为准。
