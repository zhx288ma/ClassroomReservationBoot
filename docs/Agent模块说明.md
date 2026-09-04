# 智能预约 Agent 模块

## 1. 模块目标

智能预约 Agent 是一个**受控的预约决策层**。它负责把自然语言需求转换为受限的查询工具调用，返回可预约时段候选和待确认的预约草稿；它不直接创建预约、不直接修改库存，也不绕过既有的 Submit Token、Redis Lua、MySQL 条件更新和唯一索引。

这使 Agent 与核心预约事务解耦：即使模型不可用，预约主链路和防超卖能力也不受影响。

## 2. 当前能力

`POST /agent/chat`，需要登录后的 `X-Token`。

| 意图 | 本地工具 | 权限 | 结果 |
| --- | --- | --- | --- |
| `SEARCH_AVAILABLE_SLOTS` | `search_available_slots` | 学生、管理员 | 检索 `OPEN` 且仍有名额的时段 |
| `RESERVATION_DRAFT` | `search_available_slots` | 学生、管理员 | 返回候选和待确认预约草稿 |
| `RULES` | `retrieve_policy_knowledge` | 学生、管理员 | 从 RAG 知识库检索预约、候补、签到、信用规则并返回来源 |
| `MY_RESERVATIONS` | `get_my_reservations` | 当前用户 | 查询当前用户最近预约记录 |
| `USAGE_STATISTICS` | `get_usage_statistics` | 管理员 | 读取运营统计摘要 |

示例：

```text
帮我找明天晚上 18 点到 20 点，计算机楼、至少 30 人、有投影的教室
帮我预约明天晚上 18 点到 20 点的教室
预约、候补和签到规则是什么？
```

## 3. 安全边界

1. 意图是白名单枚举，模型或规则解析器不能临时拼接 SQL、URL 或业务操作。
2. `USAGE_STATISTICS` 在服务端检查 `ADMIN` 角色，前端隐藏不是权限依据。
3. `ReservationDraft` 仅回填预约表单；用户仍须生成一次性令牌并提交预约。
4. Redis 只保存限定条数、30 分钟过期的会话短记忆；会话 Key 包含用户 ID，用户之间不共享上下文。
5. 每次 Agent 调用均写入 `tb_agent_trace`，记录 Trace ID、意图、工具轨迹、知识来源、耗时与成功状态。
6. LLM 或 Embedding 调用失败自动退回本地解析和关键词检索，不影响已有预约能力。

## 4. 可选 LLM 配置

默认 `CLASSROOM_AGENT_LLM_ENABLED=false`，系统使用确定性解析器，适合本地演示与无 Key 环境。

配置 OpenAI 兼容 Chat Completions 服务后，模型只输出固定 JSON 意图，不能直接执行工具或写入数据：

```powershell
$env:CLASSROOM_AGENT_LLM_ENABLED = "true"
$env:CLASSROOM_AGENT_LLM_API_KEY = "你的密钥"
$env:CLASSROOM_AGENT_LLM_BASE_URL = "https://api.openai.com/v1"
$env:CLASSROOM_AGENT_LLM_MODEL = "gpt-4.1-mini"
```

随后从 IDEA 重启应用。密钥只能放环境变量，不能写入前端、`application.yml` 或 Git。

## 5. RAG 知识库

管理员在“智能助手”页面可维护规则、FAQ 和操作指南，也可上传 PDF、Markdown、TXT。上传链路为“原文件留存 -> PDFBox 提取文本 -> 650 字符滑动窗口切片（100 字符重叠）-> 写入 MySQL 分块元数据 -> 调用 Embedding -> 写入 Qdrant Collection”。每份文档会记录切片数、向量数及 `INDEXED/PARTIAL/FAILED` 索引状态；扫描版 PDF 没有文字层时会被提示先进行 OCR。

知识文档分为本系统生效的 `POLICY`、`GUIDE`、`FAQ`，以及 `EXTERNAL_REFERENCE` 外部高校公开参考资料。后者可扩展助手对学籍、考试纪律、奖助、住宿等校园制度问题的解释能力，但回答必须展示来源并声明“仅供参考，不构成本系统生效校规”；与本系统 `POLICY` 冲突时以前者的本系统规则为准。当前已将北京大学公开的学生管理与校园规章汇编作为 `EXTERNAL_REFERENCE` 建立 258 个检索分块，用于验证长文档制度问答与引用边界。

`tb_agent_knowledge_document` 与 `tb_agent_knowledge_chunk` 是来源与审计库，**Qdrant 才是向量数据库**，Collection 默认为 `classroom_agent_knowledge`。查询采用向量召回与关键词召回融合；Qdrant、嵌入服务任一不可用时自动只走关键词检索，预约主链路不受影响。

没有 Embedding Key 时仍可运行本地关键词检索。开启 OpenAI 兼容 Embedding 的环境变量：

```powershell
$env:CLASSROOM_AGENT_EMBEDDING_ENABLED = "true"
$env:CLASSROOM_AGENT_EMBEDDING_API_KEY = "你的密钥"
$env:CLASSROOM_AGENT_EMBEDDING_BASE_URL = "https://api.openai.com/v1"
$env:CLASSROOM_AGENT_EMBEDDING_MODEL = "text-embedding-3-small"
$env:CLASSROOM_AGENT_VECTOR_ENABLED = "true"
$env:CLASSROOM_QDRANT_URL = "http://localhost:6333"
```

系统首次启动会自动写入三份基础知识：预约规则、签到候补信用规则、时段开放说明。

先启动 Qdrant（完整 Docker Compose 已包含该服务）：

```powershell
docker compose up -d qdrant
```

然后在管理员端“智能助手 -> Agent 知识库”上传规则 PDF；页面的“检索运行状态”必须显示“向量数据库：Qdrant”和“嵌入模型：已启用”，新文档应显示 `INDEXED` 且向量数等于或接近切片数。嵌入服务可使用 OpenAI 兼容服务或本地模型网关；API Key 仅放环境变量，不写进前端或仓库。

首次开启 Qdrant 后，点击“重建全部索引”，会先删除每份文档旧的 Qdrant points，再重新提取分块并 upsert，避免历史切片残留。接口为 `POST /agent/knowledge/rebuild`，仅管理员可访问。

知识文档列表支持删除。删除会软删除文档记录、清理 MySQL 分块、删除关联 Qdrant points，并且只会删除位于 `uploads/agent-knowledge` 目录内的上传源文件；内置规则没有源文件，因此只清理索引数据。接口为 `DELETE /agent/knowledge/{documentId}`。

## 6. 前端验证

1. 使用学生账号登录，进入“智能助手”。
2. 输入自然语言需求，点击“开始分析”。
3. 确认页面出现“混合 RAG 工具编排”、候选教室、工具轨迹和耗时。
4. 点击“填写预约表单”，检查教室、日期、时间段已回填。
5. 手动生成一次性令牌后提交预约，检查订单、候补、通知与库存。
6. 输入“签到窗口和爽约扣分规则是什么”，确认页面显示知识来源片段。
7. 管理员维护一条知识文档，或上传一份可复制文本的 PDF，检查其出现在 RAG 文档列表，且显示来源类型、切片数、向量数与索引状态；随后用学生账号提问并确认可检索。
8. 管理员在反馈表点击“AI 分析”，确认分类、优先级和建议回复只会回填输入框，仍须管理员人工发送。
9. 管理员可在页面查看 Agent Trace，核对意图、输入摘要与耗时。

## 7. 面试说明

> 我把 Agent 放在预约事务之外，采用“自然语言 -> 结构化意图 -> RAG/白名单工具 -> 用户确认 -> 原有预约链路”的模式。规章 PDF、Markdown 和文本会提取、分块，并将 Embedding 写入 Qdrant；查询走向量与关键词混合召回，回答带来源片段。系统区分本校生效规则与外部高校参考材料：外部资料只用于解释和对照，回答会明确来源及非生效边界。模型只做受限意图判断，`room_slot` 查询、权限检查和预约写入仍由 Spring Boot 服务完成。这样既有自然语言选教室体验，又避免模型幻觉或工具误调用影响库存和订单一致性；模型、Qdrant 任一不可用时，系统退回本地解析和关键词检索。

工单 Copilot 是另一条只读辅助工具：它对反馈做设备、签到、预约、账号、通用问题分类，给出优先级和建议回复，但不会自动发送、关闭工单或修改信用分。

后续可将工具注册表暴露为 MCP Server；当前已用固定用例集评估意图、权限和工具边界，这些演进均不改变预约主链路。
