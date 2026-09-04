# 智慧校园教室预约 Agent 面试与实操底稿

这份文档用于回答两个问题：

1. 项目中的 Agent、RAG、LangChain4j、Spring AI、Qdrant 到底是怎么落地的。
2. 如何从 PDF 建立知识库、如何测试检索质量、如何解释响应耗时和降级方案。

文档中的“当前结果”只引用已经在本项目代码或测试输出中验证过的数据；没有实际测量的数据不写成确定结论。

---

## 1. 一句话介绍 Agent

本项目的 Agent 是一个**受控的校园预约与校规问答助手**：它接收学生或管理员的自然语言请求，识别意图，通过只读工具查询开放的 `room_slot`、当前用户预约和规则知识库，最后返回候选教室、预约草稿或带来源的规则解释。

Agent 不直接提交预约、不直接取消预约、不直接签到、不直接修改库存。真正的预约提交仍然必须经过前端确认、Submit Token、Redis Lua、MySQL 事务和唯一索引。因此，Agent 是预约系统上层的智能交互层，而不是库存事务层。

可以这样向面试官概括：

> 我把自然语言交互和强一致业务事务分开。Agent 负责理解需求、检索知识、调用白名单只读工具和生成待确认草稿；预约成功与否仍由 Redis Lua、MySQL 条件更新、事务和唯一索引决定。这样即使模型、向量库或外部 API 不可用，也不会影响核心预约链路。

---

## 2. 当前 Agent 技术栈和职责

| 技术 | 在项目中的职责 | 为什么使用 | 失败时怎么处理 |
| --- | --- | --- | --- |
| Spring Boot 4.1.1 | Web 服务、依赖注入、配置和生命周期 | 与现有后端一致，便于整合数据库、缓存和消息组件 | Agent 模块失败不影响普通 REST 接口 |
| Spring AI | 调用 OpenAI 兼容的 Embedding 模型，统一模型访问抽象 | Alibaba Model Studio 等服务可以使用兼容接口，替换成本低 | Embedding 失败退回关键词检索 |
| LangChain4j | `@Tool` 工具声明、工具调用 Agent 和会话式回答 | 比手写模型 JSON 解析更接近 Agent 应用开发实践 | LangChain4j 或 LLM 调用失败退回本地规则解析和 RAG |
| DeepSeek / OpenAI 兼容 Chat API | 意图理解、工具选择和规则回答 | 让自然语言需求能映射到结构化工具参数 | 模型超时、格式错误或无 Key 时使用确定性解析器 |
| PDFBox 3.0.3 | 从 PDF 提取文字 | Java 项目内直接处理 PDF，不依赖外部脚本 | 扫描件无文本层时提示需要 OCR |
| Qwen Embedding | 把问题和知识片段转换为向量 | 支持语义相似度检索，能处理同义表达 | 服务不可用时保留 MySQL 关键词检索 |
| Qdrant | 保存向量和来源 Payload，执行 Top-K 向量召回 | 轻量、Docker 易部署，适合简历项目本地落地 | Qdrant 连接失败时只使用关键词召回 |
| MySQL | 保存原始文档元数据、切片、索引状态和审计信息 | 可追溯、可重建，不能把向量库作为唯一事实来源 | 向量库异常时仍可检索文本 |
| RabbitMQ | Agent 相关业务通知，例如反馈工单通知、候补通知 | 任务队列适合可靠异步动作 | 重试、死信和站内通知落库 |
| SSE | 在线用户实时接收通知 | 当前前端以单向通知为主，接入简单 | 用户离线时消息保存在 `notification` 表 |
| Micrometer / Prometheus | 观察请求和 Agent 阶段耗时 | 能区分检索慢、模型慢还是业务查询慢 | 指标不可用时保留 Trace 日志 |

当前没有把 Kafka 放进 Agent 的实时回答链路。Kafka 仍然用于预约、签到、爽约等已经发生的业务事件统计，不用于判断 Agent 是否可以预约，也不用于替代 Qdrant。

---

## 3. 完整 RAG 流程

整个流程可以分成离线入库和在线问答两部分：

```text
PDF 上传
  -> 文件校验与保存
  -> PDFBox 提取文字
  -> 清理页眉页脚和空白
  -> 按字符窗口切片并保留重叠
  -> MySQL 保存文档和 chunk
  -> Embedding 模型生成向量
  -> Qdrant upsert 向量和来源 Payload

用户提问
  -> 意图识别
  -> 规则/校规问题进入 RAG
  -> 关键词召回 + Qdrant 向量召回
  -> Reciprocal Rank Fusion 合并排序
  -> 返回带 document/chunk 来源的证据
  -> LangChain4j 只读工具编排
  -> 生成回答并记录 Trace
```

### 3.1 第一步：上传 PDF

管理员在 Agent 知识库页面上传 PDF、Markdown 或 TXT。后端首先保存源文件，并在 `tb_agent_knowledge_document` 写入文档元数据。文档会记录：

- 标题和来源文件名。
- `source_type`，例如 `PDF`、`MARKDOWN`、`TEXT`。
- `category`，例如 `POLICY`、`GUIDE`、`FAQ`、`EXTERNAL_REFERENCE`、`EVAL_DATASET`。
- `index_status`，例如 `PENDING`、`INDEXED`、`PARTIAL`、`FAILED`。
- `chunk_count`、`vector_count` 和失败原因。

分类很重要：

- `POLICY`：本系统真正生效的预约、签到、候补和信用规则。
- `GUIDE`：操作说明，例如如何生成 Submit Token。
- `FAQ`：常见问题。
- `EXTERNAL_REFERENCE`：外部学校公开资料，只能参考，不能冒充本校生效校规。
- `EVAL_DATASET`：只用于离线评测，正常学生问答不会混入这批数据。

### 3.2 第二步：PDF 是怎么“切”的

这里的“切 PDF”不是把 PDF 物理裁成很多新的 PDF 文件，而是：

1. 使用 PDFBox 逐页读取 PDF 的文字层。
2. 把所有页面文字合并为可检索文本。
3. 按固定字符窗口切成多个知识片段，也叫 chunk。
4. 相邻 chunk 保留一部分重叠，避免一句话刚好被切在两个片段之间。

当前实现使用约 **650 个字符的窗口**，相邻片段约 **100 个字符重叠**。示意如下：

```text
原文：        [------------------------------------------------------------]
chunk 1：     [0................................................650]
chunk 2：                         [550.......................1200]
chunk 3：                                              [1100........1750]
重叠区域：                     100 字符左右
```

这样做的理由是：

- 片段过大，检索返回的上下文噪声大，模型容易抓错重点。
- 片段过小，规则的条件、例外和处罚结果可能被拆开，回答不完整。
- 适当重叠可以提高边界句子的召回概率。
- 650 字符是当前项目的工程折中，并不是所有 PDF 的最佳值。

当前代码更偏向通用字符窗口，适合第一版落地；对于章、节、条款结构明显的校规，下一步可以升级为“标题感知切分”：先识别“第一章”“第一条”等标题，将章节标题复制到其下每个 chunk 的 metadata 中，再做窗口切分。这样能改善“考试纪律”“奖学金评审”等需要定位具体章节的问题。

### 3.3 PDF 提取时要注意什么

PDF 并不等于可检索文本，常见情况如下：

| PDF 类型 | PDFBox 结果 | 处理方式 |
| --- | --- | --- |
| 文字型 PDF | 可以直接提取中文 | 直接切分和向量化 |
| 扫描图片型 PDF | 提取结果为空或极少 | 先 OCR，再进入切分流程 |
| 多栏排版 | 文字顺序可能错乱 | 需要版面分析或人工检查 |
| 页眉页脚重复 | 每个 chunk 都会带重复内容 | 清理固定页眉、页脚、页码 |
| 表格型 PDF | 表格顺序可能不自然 | 转 Markdown/CSV 或保留表头 |
| 超长法规 | chunk 数量较多，嵌入耗时明显 | 分批 Embedding、记录进度和失败项 |

面试时不要说“上传 PDF 后模型自动理解全部内容”。准确说法是：

> 系统先把 PDF 转成文本，再进行分块、Embedding 和向量索引；在线回答只取与问题最相关的少量片段，不把整本 PDF 直接塞给模型。

### 3.4 第三步：写入 MySQL

MySQL 保存的是可追溯的文本和索引状态，核心关系是：

```text
tb_agent_knowledge_document
  1 ---- N  tb_agent_knowledge_chunk
```

每个 chunk 至少保存：

- `document_id`
- `chunk_index`
- `content`
- `embedding_json`（用于审计或重建，不能替代 Qdrant 查询）
- `vector_point_id`
- 创建时间和更新状态

为什么不只保存 Qdrant：

- Qdrant 擅长相似度查询，不擅长完整业务审计。
- 删除、重建和重新嵌入时需要知道原文来源。
- 可以核对回答引用的 chunk 是否真的包含证据。
- Qdrant 丢失后，可以从 MySQL 重新生成向量。

### 3.5 第四步：生成 Embedding

对 chunk 调用 Embedding 模型：

```text
“学生应当在预约开始前15分钟到开始后15分钟内签到……”
        -> [0.012, -0.083, 0.221, ...]
```

用户问题也用相同模型生成向量。只有问题向量和知识片段向量使用同一个向量空间，余弦相似度才有意义。

当前项目通过 Spring AI 的 `EmbeddingModel` 或 OpenAI 兼容 `/embeddings` 接口调用模型，并配置 1024 维向量。索引阶段不是逐 chunk 请求：`AgentEmbeddingService.embedBatch` 按 20 条一批调用 `qwen3.7-text-embedding`，返回结果按响应中的 `index` 还原到输入顺序。Qdrant Collection 使用 Cosine Distance。

为什么批量大小是 20：这是当前 `qwen3.7-text-embedding` 同步接口允许的单请求文本上限。以 258 个 chunk 为例，Embedding 请求从约 258 次下降到约 13 次，减少网络往返和限流风险；某批失败时，该批向量保留为空并将文档标记为 `PARTIAL`，关键词检索仍可工作。

Embedding 不是“答案”，只是把文本转换成可比较的数值表示。它能发现“爽约会扣分”和“未签到有什么处罚”之间的语义相似，但不能保证条款级事实一定正确，所以仍然需要来源引用、评测和人工抽查。

### 3.6 第五步：写入 Qdrant

每一个 Qdrant point 包含：

```json
{
  "id": 12345,
  "vector": [0.012, -0.083, 0.221],
  "payload": {
    "documentId": 25,
    "chunkId": 12345,
    "title": "北京大学学生管理与校园规章汇编（外部参考）",
    "category": "EXTERNAL_REFERENCE",
    "content": "原始 chunk 文本"
  }
}
```

`category` 放在 Payload 中，是为了在评测时隔离数据集。例如 `EVAL_DATASET` 的检索不能被生产规则或外部长文档污染；外部高校资料也不能被误当成本系统规则。

Qdrant 写入同样不是逐 point 请求。`AgentVectorStoreService.upsertBatch` 每批最多发送 64 个 point；258 个向量约需 5 次 upsert。MySQL 仍保存文档、chunk 和索引状态，Qdrant 可以从 MySQL 数据重建。

当前实际建立过的外部参考文档是北京大学学生管理与校园规章汇编，入库后约有 **258 个 chunk 和 258 个向量**。它的用途是验证长文档制度问答，不代表这些规则直接适用于本系统。

### 3.7 第六步：在线混合检索

在线提问“未签到会不会扣信用分”时，系统会走两路：

1. **关键词召回**：在 MySQL chunk 文本中按关键词匹配并排序。
2. **向量召回**：问题生成 Embedding 后，向 Qdrant 查询 Top-K 相似向量。

只使用向量可能漏掉精确的制度名称、时间、数字和专有名词；只使用关键词又容易被同义表达影响，所以项目采用混合召回。

### 3.8 第七步：RRF 融合

两路结果的分数不能直接相加，因为关键词分数和向量相似度不是同一种量纲。项目使用 Reciprocal Rank Fusion，核心形式是：

```text
RRF(chunk) = Σ 1 / (k + rank_i(chunk))
```

其中 `rank_i` 是某个召回通道里的名次，`k` 当前配置为 60 左右。一个片段如果同时出现在关键词和向量结果中，即使两路原始分数不同，也会因为名次都靠前而得到更高融合分。

RRF 的优点：

- 不需要手动把 BM25 分数和 cosine 分数校准到同一尺度。
- 对关键词精确命中和语义相似命中都比较友好。
- 可以增加第三个召回通道而不改变原有分数含义。

### 3.9 第八步：Cross-Encoder 精排

RRF 解决多路召回分数不可比的问题，但它只利用每一路的名次，没有联合理解“问题是否被这个 chunk 直接回答”。当前项目把 RRF Top 30 作为粗排候选，批量发送给百炼 `qwen3-rerank`。该模型会联合编码 query 和每个 document，因此属于 Cross-Encoder 精排。

纯粹使用 Cross-Encoder 分数替换 RRF 曾出现一个真实退化：Top-1 和 MRR 提升，但部分原本位于第 3 名的正确 chunk 被挤出，HOLDOUT Recall@3 从 73.33% 降到 66.67%。最终采用加权融合：

```text
normalizedRrf = (rrfScore - minRrf) / (maxRrf - minRrf)
finalScore = 0.35 * crossEncoderScore + 0.65 * normalizedRrf
```

最终固定测试集 Recall@1 从 40.00% 提升到 46.67%，Recall@3 从 73.33% 提升到 80.00%，MRR@3 从 54.44% 提升到 62.22%。代价是平均检索耗时从 178 ms 增加到 460 ms。精排 API 失败时返回空结果，检索服务继续使用原 RRF 顺序。

### 3.10 第九步：把证据交给 Agent

检索结果不是直接当作最终答案，而是转换成带来源的 `AgentKnowledgeSource`：

- 文档 ID、chunk ID。
- 文档标题和分类。
- 片段摘要或正文。
- 检索得分或排名。

回答会展示来源片段。对于 `EXTERNAL_REFERENCE`，必须补充：外部高校资料仅供参考，不构成本系统生效校规；如果和本系统 `POLICY` 冲突，以本系统正式规则为准。

---

## 4. Agent 是怎么调用工具的

### 4.1 意图识别

当前支持的主要意图包括：

- `SEARCH_AVAILABLE_SLOTS`：搜索开放且有余量的教室时段。
- `RESERVATION_DRAFT`：搜索并生成待确认预约草稿。
- `RULES`：查询预约规则、签到、候补、信用分或校园制度。
- `MY_RESERVATIONS`：查询当前用户自己的预约。
- `USAGE_STATISTICS`：管理员查看运营统计。
- `WRITE_ACTION_REFUSED`：拒绝直接预约、直接取消、直接签到、修改库存等写操作。

有模型时，可以让模型从固定枚举中选择意图；模型不可用时，使用本地关键词和正则解析日期、时间段、人数、楼栋、设备。

### 4.2 `@Tool` 工具白名单

LangChain4j 的工具声明只暴露查询能力，例如：

```java
@Tool("Searches administrator-opened classroom slots. It never creates a reservation.")
public List<Map<String, Object>> searchOpenSlots(...)
```

当前工具主要包括：

- `searchOpenSlots`：查询管理员开放的 `room_slot`。
- `retrievePolicyKnowledge`：检索规则和外部参考知识，并返回来源。
- `getMyReservations`：只读取当前登录用户自己的预约。

工具层不包含 `createReservation`、`cancelReservation`、`checkIn` 等写方法。这是代码层面的安全边界，不依赖提示词自觉。

### 4.3 为什么预约只返回草稿

自然语言“帮我预约”存在几个风险：日期可能解析错误，用户可能没有看清教室，名额可能在模型响应期间发生变化。因此 Agent 只返回：

```text
roomId
roomSlotId
reserveDate
timeSlot
requiresConfirmation=true
```

前端把草稿回填到预约表单，用户确认后再生成一次性令牌并提交。最终提交重新校验状态和库存。

---

## 5. 安全边界和降级兜底

### 5.1 模型不可用

现象：DeepSeek 超时、API Key 缺失、响应不是合法 JSON。

处理：

1. 记录模型调用失败日志。
2. 回到确定性意图解析。
3. 规则问题直接走本地 RAG。
4. 搜索问题使用日期、时间段、人数、楼栋和设备的本地解析。

预约主链路完全不依赖模型。

### 5.2 Embedding 不可用

现象：外部 Embedding API 限流、密钥过期或网络失败。

处理：

- 新文档索引状态可以是 `PARTIAL` 或 `FAILED`，保留失败原因。
- 已有文本仍保存在 MySQL。
- 查询退回关键词检索。
- 管理员修复配置后执行“重建全部索引”。

### 5.3 Qdrant 不可用

现象：容器未启动、端口错误、Collection 不存在。

处理：

- Qdrant 查询捕获异常并返回空向量结果。
- 系统继续执行 MySQL 关键词检索。
- `GET /agent/knowledge/status` 显示当前向量库状态。
- 恢复 Qdrant 后从 MySQL chunk 重建。

### 5.4 检索不到证据

不能让模型凭常识编造答案。当前返回“知识库暂未命中”，并建议用户补充制度名称、事项或关键词。对于规则问答，低置信度时应优先拒答或要求澄清。

### 5.5 工具误调用或越权

三层防线：

1. 提示词声明只允许只读工具。
2. `@Tool` 实际只暴露只读方法。
3. 服务端仍做角色和当前用户校验，前端隐藏不算权限控制。

直接修改预约、库存、签到的请求会进入 `WRITE_ACTION_REFUSED`，而不是交给模型尝试执行。

### 5.6 外部校规误用

外部资料单独标记为 `EXTERNAL_REFERENCE`。回答必须展示来源和适用边界，不得说“系统规定”。本系统的 `POLICY` 优先级高于外部资料。

---

## 6. 当前项目的评测设计

不能只看“回答听起来像不像”，所以当前评测拆成五层：

1. 检索：证据是否进入 Top-K。
2. 引用：回答引用的文档和 chunk 是否正确。
3. 忠实度：回答事实是否能被引用片段支持。
4. Agent 工作流：意图、工具选择和拒绝写操作是否正确。
5. 性能：路由、检索、模型工具调用和完整响应分别耗时多少。

### 6.1 数据集划分

当前有两套数据：

#### 生成的项目规则回归集

- 20 个 `EVAL_DATASET` 文档。
- 20 个中文改写问题。
- 用于快速回归和比较 lexical、vector、RRF。
- 不用于宣称真实高校制度问答质量。

#### 外部长文档人工锚定集

- 基于 189 页北京大学学生管理与校园规章汇编。
- 共 60 个问题，每个问题有人工确认的 expected anchor 片段。
- DEV 45 条：用于调整切分、查询规范化和融合参数。
- HOLDOUT TEST 15 条：只用于最终验证，不能反过来调参。

这样做是为了避免把测试问题的答案泄漏到提示词或切片规则里。

### 6.2 RAG 指标解释

#### Recall@1

期望证据是否排在第 1 位：

```text
Recall@1 = Top1 命中问题数 / 问题总数
```

#### Recall@3

期望证据是否出现在前 3 个结果中：

```text
Recall@3 = Top3 命中问题数 / 问题总数
```

#### MRR@3

越靠前权重越高：

```text
MRR@3 = 平均(1 / 正确证据排名)
```

如果正确证据排第 1，贡献 1；排第 2，贡献 0.5；排第 3，贡献约 0.333；Top3 之外贡献 0。

### 6.3 当前已经测得的 RAG 结果

#### 20 条项目规则回归集

检索策略为 MySQL 关键词召回 + Qdrant 向量召回 + RRF：

| 数据集 | Recall@1 | Recall@3 | MRR@3 |
| --- | ---: | ---: | ---: |
| 20 条生成项目规则 | 0.9000 | 0.9500 | 0.9250 |

已知漏召回问题是“最早和最晚可以什么时候签到？”。这说明当前字符切分和查询词匹配对时间边界类问题仍有改进空间。

#### 189 页外部参考文档

| 数据集 | 条数 | Recall@1 | Recall@3 | MRR@3 |
| --- | ---: | ---: | ---: | ---: |
| DEV | 45 | 0.5111 | 0.8444 | 0.6519 |
| HOLDOUT TEST | 15 | 0.4000 | 0.7333 | 0.5444 |

这组结果比生成数据明显低，反而更有价值：它说明真实长文档中，考试纪律、奖学金、转学、休学学籍等问题需要标题感知切分、领域同义词和查询扩展，不能拿小规模生成数据的结果冒充生产准确率。

面试时应该诚实地说：

> 项目已经建立了可重复的检索评测，并且在 20 条项目规则回归集上取得 Recall@1 0.90、Recall@3 0.95；在 189 页真实外部文档的 15 条留出集上 Recall@3 为 0.7333。后者作为当前基线，用于继续优化长文档切分和查询扩展，而不是包装成生产级准确率。

### 6.4 Agent 工作流结果

当前 6 条 Agent 工作流测试结果：

| 指标 | 结果 |
| --- | ---: |
| 完成率 | 1.0000 |
| 意图准确率 | 1.0000 |
| 期望工具召回率 | 1.0000 |
| 禁止写工具调用率 | 1.0000 |
| 必要事实覆盖率 | 1.0000 |
| 平均响应时间 | 2169 ms |
| P50 | 815 ms |
| P95 | 5844 ms |
| P99 | 5844 ms |

这组耗时包含模型调用和工具编排，外部 API 的网络波动会显著影响 P95/P99，不能直接等同于 MySQL 或 Qdrant 的耗时。

### 6.5 为什么当前没有首 Token 指标

目前 LangChain4j 使用的是同步 Tool Calling，接口拿到完整回答后才返回，所以 `firstTokenMs` 当前是 `N/A`，不能用总耗时代替首 Token 耗时。

如果后续要正式测 TTFT，需要：

1. 使用 `StreamingChatModel` 或对应流式客户端。
2. 记录发送请求时间。
3. 收到第一个 token 回调时记录 `firstTokenMs`。
4. 最后一个 token 到达时记录完整回答耗时。
5. 分别统计首 Token、完整响应、检索和工具阶段的 P50/P95/P99。

---

## 7. 如何自己完整验证一次

### 7.1 启动 Qdrant

在项目目录执行：

```powershell
docker compose up -d qdrant
docker ps
```

确认 `classroom-qdrant` 正常运行，浏览器打开 `http://localhost:6333/collections` 能看到 Collection。

### 7.2 配置 Embedding 和 LLM

密钥只配置在 IDEA 的 Run Configuration 环境变量或当前 PowerShell 会话中，不要写入 Git：

```powershell
$env:CLASSROOM_AGENT_VECTOR_ENABLED = "true"
$env:CLASSROOM_QDRANT_URL = "http://localhost:6333"
$env:CLASSROOM_AGENT_EMBEDDING_ENABLED = "true"
$env:CLASSROOM_AGENT_EMBEDDING_API_KEY = "你的Embedding密钥"
$env:CLASSROOM_AGENT_EMBEDDING_BASE_URL = "你的OpenAI兼容Embedding地址"
$env:CLASSROOM_AGENT_EMBEDDING_MODEL = "qwen3.7-text-embedding"
$env:CLASSROOM_AGENT_EMBEDDING_DIMENSIONS = "1024"

$env:CLASSROOM_AGENT_LLM_ENABLED = "true"
$env:CLASSROOM_AGENT_LLM_API_KEY = "你的Chat密钥"
$env:CLASSROOM_AGENT_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:CLASSROOM_AGENT_LLM_MODEL = "deepseek-chat"
```

实际项目中还要根据 `application.yml` 的前缀确认配置是否被正确读取。启动后用 Swagger 调：

```text
GET /agent/knowledge/status
```

期望看到：

- 向量数据库为 Qdrant。
- Embedding 已启用。
- Collection 名称正确。
- 降级策略为关键词检索。

### 7.3 上传和重建 PDF

1. 以管理员登录前端。
2. 打开“智能助手 -> Agent 知识库”。
3. 上传文字型 PDF。
4. 确认文档状态从 `PENDING` 变为 `INDEXED`。
5. 检查 `chunkCount` 大于 0。
6. 检查 `vectorCount` 与 chunk 数量一致或接近。
7. 如果之前先关闭了向量服务，打开 Qdrant 后调用：

```text
POST /agent/knowledge/rebuild
```

8. 再次检查文档状态和 Qdrant Collection。

### 7.4 手工测试 RAG

登录学生账号，在 Agent 页面依次提问：

```text
预约成功后什么时候可以签到？
未签到会不会扣信用分？
满员以后如何进入候补？
北大学生奖学金评审应参考哪份文件？请说明资料来源和适用边界。
```

检查：

- 是否识别为规则问题。
- 是否显示命中的来源标题和片段。
- 外部资料是否明确标注“仅供参考”。
- 是否出现工具轨迹。
- 是否记录 `retrievalMs`、`modelToolCallingMs` 和 `totalResponseMs`。

### 7.5 调评测接口

管理员在 Swagger 中执行：

```text
POST /agent/evaluations/corpus/seed
GET  /agent/evaluations/retrieval
GET  /agent/evaluations/external-policy/retrieval
POST /agent/evaluations/external-policy/answers
POST /agent/evaluations/agent
```

建议每次记录：

- 测试日期。
- Git commit 或代码版本。
- Embedding 模型和维度。
- Qdrant Collection 名称。
- 文档数量、chunk 数量和数据集版本。
- Recall@1、Recall@3、MRR@3。
- 引用正确率、忠实度代理指标和人工正确率。
- Agent 平均、P50、P95、P99。

---

## 8. 面试高频问题与回答思路

### 为什么不用全文搜索，只用向量？

全文或关键词搜索对制度名称、时间、数字和专有名词很准确，但对“爽约处罚”和“未签到扣分”这种表达变化不够鲁棒。向量搜索能处理语义近似，但可能忽略精确条款。因此采用关键词 + 向量双路召回，再用 RRF 融合。

### 为什么不用向量库替代 MySQL？

Qdrant 负责相似度查询，不负责业务事务、权限、来源审计和文档版本管理。MySQL 保存原文、chunk、状态和来源，Qdrant 保存检索向量；两者职责不同。

### 为什么不把整本 PDF 放进提示词？

整本 PDF 可能超过上下文窗口，也会增加费用和延迟，模型还容易被无关章节干扰。RAG 先召回少量证据，再把相关片段交给模型，成本和可解释性更好。

### chunk 为什么是 650 字符？

这是当前项目的工程折中：保证一个规则片段不会太大，同时用 100 字符左右重叠减少边界截断。它不是固定真理，真实长文档评测结果已经说明需要继续升级为标题感知和条款感知切分。

### RAG 检索到了，为什么回答仍然可能错？

检索命中只说明证据进入候选集，不代表模型正确引用、没有遗漏或没有添加证据外事实。所以还要评估 Citation Correctness、Faithfulness、人工正确率和拒答准确率。

### Agent 和普通 Chatbot 的区别是什么？

普通 Chatbot 主要是问答；本项目 Agent 具备意图路由、受控工具调用、会话上下文、RAG 来源、工具轨迹、权限边界和失败降级。但它不会拥有无限工具权限，工具白名单和业务服务仍由后端控制。

### 为什么不让 Agent 直接预约？

预约涉及库存、幂等、并发和用户确认。模型输出不是事务条件，不能作为超卖控制。Agent 只能生成草稿，最终请求重新经过 Redis Lua、MySQL 条件更新和唯一索引。

### 为什么 DeepSeek 和 LangChain4j 都需要？

DeepSeek 是模型服务，负责生成或选择；LangChain4j 是应用编排层，负责把 Java 方法暴露成工具、处理工具调用协议和组织对话。一个是模型能力，一个是 Agent 集成框架，职责不同。

### 为什么又用了 Spring AI？

当前项目让 Spring AI 负责 Embedding 模型访问，让 LangChain4j 负责 Agent Tool Calling。Spring AI 的模型抽象便于替换向量模型，LangChain4j 的 `@Tool` 对 Java 工具编排更直接。两者没有放进预约事务核心链路。

### Embedding API 失败怎么办？

新文档可以标记 `FAILED` 或 `PARTIAL`，MySQL 文本仍保留；在线检索降级为关键词检索；服务恢复后管理员重建索引。这样知识库暂时降级，但预约功能继续可用。

### Qdrant 挂了怎么办？

捕获 Qdrant REST 异常，返回空向量结果，使用 MySQL 关键词结果。恢复后从 MySQL chunk 重新 upsert。面试中要强调：向量库是加速和语义增强组件，不是唯一数据源。

### 外部学校规则会不会污染系统规则？

通过 `category` 分层。生产规则是 `POLICY`，外部资料是 `EXTERNAL_REFERENCE`，离线数据是 `EVAL_DATASET`。回答中展示来源，外部资料只作参考；评测时还会按 category 过滤，避免数据集互相污染。

### 如何证明不是“看起来很智能”？

使用固定数据集和留出集评测 Recall@1、Recall@3、MRR@3；对答案评估引用正确率、忠实度和人工正确率；对 Agent 评估意图准确率、工具召回率和禁止写操作率；对性能记录检索、模型工具调用和完整回答的分阶段耗时。

---

## 9. 当前不足和后续升级路线

当前已经具备可面试的 Agent 应用雏形：真实 Embedding、Qdrant、PDF 入库、混合检索、RRF、LangChain4j `@Tool`、安全拒答、评测集和阶段耗时。但不应把它包装成已经完成所有生产级能力。

优先级建议：

1. 标题感知和条款感知 PDF 切分。
2. 页眉页脚、页码和重复段落清理。
3. 查询规范化和同义词扩展，例如“北大”归一为“北京大学”，“爽约”归一为“未签到”。
4. 记录每次检索的 query、候选排名和最终引用，支持离线误差分析。
5. 完成外部 15 条 holdout 的人工答案正确率标注。
6. 使用流式 ChatModel 测量真正的首 Token 延迟。
7. 对 Embedding 和 Chat API 增加超时、指数退避、熔断和预算限制。
8. 文档版本化，规则更新后保留旧版本和生效时间。
9. 对敏感信息做脱敏，不将学生个人预约内容发送给外部模型。
10. 后续可把工具注册抽象成 MCP Server，但仍要保留服务端权限和写操作确认。

---

## 10. 简历表达建议

推荐写成下面这种可验证的表达：

> 构建校园预约与校规问答 Agent：基于 PDFBox 完成规则文档解析与滑动窗口切分，使用 Qwen Embedding + Qdrant 建立向量索引，结合 MySQL 关键词召回和 RRF 融合实现混合检索；通过 LangChain4j `@Tool` 编排开放时段搜索、规则检索和个人预约查询，并以工具白名单、用户权限和确认式草稿限制模型写操作。建立 20 条项目规则回归集和 60 条外部制度人工锚定集，按 DEV/HOLDOUT 评估 Recall@1、Recall@3、MRR@3、引用和忠实度；项目规则回归集 Recall@1 0.90、Recall@3 0.95。

如果写响应时间：

> 在 6 条 Agent 工作流评测中，完成率、意图准确率、期望工具召回率和禁止写工具调用率均为 100%；完整响应平均 2169ms，P50 815ms，P95/P99 5844ms。该结果包含外部模型网络耗时，首 Token 因当前采用同步 Tool Calling 暂未测量。

不要写：

- “模型保证预约不超卖”。超卖由 Redis Lua、MySQL 条件更新和唯一索引保证。
- “RAG 准确率 95%”。当前 0.95 是 20 条生成项目规则集的 Recall@3，不是通用答案准确率。
- “首 Token 815ms”。815ms 是 Agent 完整响应评测的 P50，不是 TTFT。
- “北京大学规章就是本校规则”。它在系统中是 `EXTERNAL_REFERENCE`，只作参考。

---

## 11. 最后用一段话讲完整技术闭环

> 管理员上传规则 PDF 后，系统通过 PDFBox 提取文字，按约 650 字符窗口和 100 字符重叠切成 chunk，原文和索引状态保存到 MySQL，同时调用 Qwen Embedding 生成 1024 维向量写入 Qdrant。用户提问时先做意图识别，规则问题走 MySQL 关键词召回和 Qdrant 向量召回，再用 RRF 合并 Top-K，并把带来源的证据交给 LangChain4j。LangChain4j 只允许调用查询开放时段、检索规则和读取本人预约等白名单工具，预约请求只能生成草稿，最终提交仍由原有 Redis Lua、MySQL 事务和唯一索引完成。模型、Embedding 或 Qdrant 失败时分别降级到本地意图解析、关键词检索或 MySQL 文本数据；所有请求记录 Agent Trace，并用离线回归集、真实长文档留出集、引用正确率、忠实度和分阶段延迟验证效果。
