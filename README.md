# 智慧校园教室预约与智能问答平台

基于 Spring Boot 4 的校园空间资源调度系统。项目以 `room_slot` 为核心资源模型，覆盖管理员开放教室时段、学生按名额预约、满员候补、签到与信用治理、异步通知、实时消息、统计分析，以及基于 LangChain4j + Qdrant 的预约助手和校规 RAG 问答。

> 当前版本重点完成学生与管理员主流程。教师整间教室申请已经预留 `tb_teacher_booking` 数据模型，但审批接口与前端流程仍属于后续扩展，不作为当前已交付功能宣传。

## 项目看点

- **业务模型合理**：学生预约的是一个开放教室时段中的一个名额，而不是占用整间教室；管理员统一决定哪些 `room_slot` 可以被预约。
- **并发链路可验证**：Submit Token、用户级 Redisson 锁、Redis Lua、MySQL 条件更新和唯一索引共同保证名额边界、重复预约和同时间段冲突。
- **候补与履约闭环**：满员自动候补，取消或爽约释放名额后按 FIFO 补位；签到窗口、未签到扫描和信用流水形成完整履约治理。
- **消息职责清晰**：RabbitMQ 负责业务通知，Kafka 负责已发生事件的统计分析，SSE 负责在线推送，Outbox 解决数据库与消息系统的最终一致性。
- **检索与事务分离**：Caffeine + Redis 服务热点查询，Elasticsearch 服务教室复杂检索；预约是否成功始终回到 Redis Lua 与 MySQL 事务判断。
- **Agent 具备工程边界**：LangChain4j 只向模型暴露只读工具，RAG 使用关键词和向量双路召回、RRF 融合及 Cross-Encoder 精排，并提供降级、调用追踪和离线评测。

## 页面预览

| 登录页 | 学生工作台 | 管理员工作台 |
| --- | --- | --- |
| ![登录页](docs/screenshots/frontend-login.png) | ![学生工作台](docs/screenshots/frontend-student-dashboard.png) | ![管理员工作台](docs/screenshots/frontend-admin-dashboard.png) |

前端由 Spring Boot 静态资源直接提供，不需要单独启动 Node 服务。登录后按照 `ADMIN` 和 `USER` 角色展示不同菜单，登录页和注册页不显示业务侧边栏。

## 核心业务

### 为什么核心是 room_slot

`room_slot` 表示“某间教室 + 某个日期 + 某个时间段”的可调度资源。教室容量属于 `room`，但某一次是否开放、可以预约多少人、已经预约多少人以及当前状态都属于 `room_slot`。

这种建模让以下行为作用于同一个资源对象：

1. 管理员手动或批量创建可预约时段。
2. 学生预约时段中的一个名额，多名学生可以预约同一时段。
3. 时段满员后，学生进入与该时段绑定的候补队列。
4. 管理员关闭或维护时段时，后端检查是否已有有效预约。
5. 预约、取消、签到、爽约和候补事件都携带 `room_slot_id`，便于统计与审计。

### 角色与能力

| 角色 | 当前能力 |
| --- | --- |
| 学生 | 注册登录、查询开放时段、预约名额、进入或退出候补、取消预约、签到、查看信用分、通知和个人预约、提交反馈、使用预约助手与规则问答 |
| 管理员 | 维护教室、手动或批量创建时段、开放/关闭/维护/删除无业务数据的时段、查看全量预约与候补、回复反馈、同步 ES 索引、查看 Redis/MQ/Kafka 统计和审计信息、管理 Agent 知识库 |
| 教师 | 当前仅预留整间教室申请的数据结构，审批业务尚未形成完整交付流程 |

### 状态模型

| 对象 | 状态 |
| --- | --- |
| 教室时段 | `CLOSED`、`OPEN`、`MAINTENANCE`、`TEACHER_BOOKED`、`EXPIRED` |
| 学生预约 | `WAIT_AUDIT`、`RESERVED`、`FAILED`、`CANCELED`、`SIGNED`、`NO_SHOW` |
| 候补记录 | `WAITING`、`PROMOTED`、`CANCELED`、`SKIPPED`、`EXPIRED` |

管理员不能直接关闭、维护或删除已有有效预约/候补的时段。过期候补由定时任务关闭，历史预约与候补数据保留用于审计，而不是通过物理删除掩盖状态变化。`room_slot` 已定义 `EXPIRED` 状态，但自动过期流转仍属于待补能力。

## 系统架构

```mermaid
flowchart LR
    WEB[Web 前端 / Swagger / JMeter] --> APP[Spring Boot 4 REST 服务]
    APP --> SEC[Spring Security + JWT]
    APP --> MYSQL[(MySQL 8)]
    APP --> REDIS[(Redis 7)]
    APP --> RMQ[RabbitMQ]
    APP --> KAFKA[Kafka]
    APP --> ES[Elasticsearch]
    APP --> QDRANT[Qdrant]
    APP --> LLM[OpenAI 兼容模型 API]
    RMQ --> NOTICE[通知消费者]
    NOTICE --> MYSQL
    NOTICE --> SSE[SSE 在线推送]
    KAFKA --> STAT[统计消费者]
    STAT --> MYSQL
    APP --> ACT[Actuator + Micrometer]
    ACT --> PROM[Prometheus]
    PROM --> GRAFANA[Grafana]
```

### 技术栈

| 层次 | 技术 | 项目中的职责 |
| --- | --- | --- |
| 基础框架 | Java 17、Spring Boot 4.1.1、MyBatis 4 | REST 服务、依赖注入、事务和数据访问 |
| 认证授权 | Spring Security、JWT、Redis | 无状态过滤器链、HS256 Token、登录态校验与主动注销 |
| 核心存储 | MySQL 8、HikariCP、Flyway | 预约事务、唯一约束、连接池和版本化数据库迁移 |
| 并发控制 | Redis、Lua、Redisson | 原子扣减、重复/时间冲突校验、分布式锁和补偿状态 |
| 缓存 | Caffeine、Redis、ZSet | 单实例热点缓存、跨实例共享缓存、热门教室排行 |
| 异步消息 | RabbitMQ、Outbox、DLQ | 业务通知异步化、失败重试、死信与人工补偿 |
| 事件分析 | Kafka、event outbox | 预约/取消/签到/爽约事件流和统计结果落库 |
| 实时通信 | SSE、`SseEmitter` | 在线用户通知中心实时更新，断线自动重连 |
| 搜索 | Elasticsearch 7.17 | 按关键词、楼栋、容量、设备和教室类型检索候选教室 |
| AI 应用 | LangChain4j、Spring AI、PDFBox | 只读工具编排、模型适配、PDF 解析、Agent 调用追踪 |
| RAG | Qdrant、Embedding、RRF、Cross-Encoder | 向量存储、混合召回、融合排序和语义精排 |
| 可观测性 | Actuator、Micrometer、Prometheus、Grafana | 健康检查、HTTP/JVM/连接池及业务指标采集 |
| 测试交付 | JUnit、Mockito、Testcontainers、JMeter、Docker Compose、Swagger | 单元/集成/接口/并发测试和本地环境编排 |

## 预约一致性设计

### 请求流程

```mermaid
sequenceDiagram
    participant U as 学生
    participant A as Spring Boot
    participant R as Redis
    participant M as MySQL
    participant O as Outbox

    U->>A: 获取 Submit Token
    A->>R: 写入一次性令牌并设置 TTL
    U->>A: 提交 room_slot + Submit Token
    A->>R: 原子消费 Submit Token
    A->>R: 获取用户级 Redisson 锁
    A->>M: 校验时段 OPEN、日期和信用分
    A->>R: 执行 Lua 原子校验并扣减名额
    R-->>A: 成功 / 满员 / 重复 / 时间冲突
    alt 有名额
        A->>M: 条件更新 reserved_count
        A->>M: 插入预约单，唯一索引兜底
        A->>O: 同事务写通知和领域事件
        A-->>U: RESERVED
    else 已满
        A->>M: 幂等插入 WAITING 候补
        A->>O: 写候补通知和事件
        A-->>U: WAITLIST
    end
```

预约主链路依次完成：

1. **Submit Token**：Redis 中的一次性令牌在提交时原子删除，阻止按钮连点和请求重放。
2. **用户级锁**：`lock:reserve:user:{userId}` 串行化同一用户的并发预约请求，降低跨时段重复提交竞争。
3. **业务校验**：MySQL 校验 `room_slot` 存在、状态为 `OPEN`、日期时间合法且信用分达到阈值。
4. **Redis Lua**：在一次脚本中检查剩余名额、当前用户是否已预约该 slot、是否占用了同一日期时间段，并原子扣减名额和写预约标记。
5. **MySQL 条件更新**：`available_capacity > 0 AND status = OPEN` 时才更新，数据库作为最终事实来源。
6. **唯一索引**：预约单的 `active_key = userId:date:timeSlot` 保证同一用户同一时间段只有一条有效预约；取消和爽约后将其置空。
7. **事务补偿**：Redis 已扣减而数据库事务失败时，通过事务同步回调恢复库存、用户集合和时间占用 Key；另有时段对账接口修正展示计数。

核心数据库更新等价于：

```sql
UPDATE tb_room_slot
SET reserved_count = reserved_count + 1,
    available_capacity = available_capacity - 1,
    version = version + 1
WHERE id = :slotId
  AND status = 1
  AND available_capacity > 0;
```

Kafka、Elasticsearch、Caffeine 和大模型均不参与预约成功判定。即使这些可选组件不可用，预约一致性仍由 Redis 与 MySQL 负责。

## 候补、签到与信用治理

### 候补

- 时段满员后通过 MySQL `INSERT IGNORE` 和唯一约束幂等加入 `WAITING` 队列。
- 第一版明确采用 **FIFO**，查询顺序以 `create_time` 为主；`priority_score` 字段仅为后续按信用分排序预留。
- 用户取消预约或定时任务释放名额后触发补位；后台任务也会周期扫描“有空位且有候补”的时段。
- 取消预约、取消候补使用订单/候补维度的 Redisson 锁，并以 `WHERE status = 原状态` 的条件更新避免重复流转。
- 补位前重新检查用户是否已在同一时间段有有效预约，冲突用户标记为 `SKIPPED`，随后尝试下一位。
- 过期候补标记为 `EXPIRED`，保留历史记录并发送通知。

### 签到与信用分

- 签到窗口为开始前 15 分钟到开始后 15 分钟。
- 签到接口校验当前用户、预约状态、签到码和时间窗口，再以条件更新将状态改为 `SIGNED`，并通过 `reservation_id` 唯一约束防止重复签到。
- 定时任务每分钟扫描超过窗口仍为 `RESERVED` 的预约，将其标记为 `NO_SHOW`。
- 正常签到加 1 分，爽约扣 5 分；所有变动写入 `tb_credit_record`，账户余额写入 `tb_credit_account`。
- 信用分低于 60 时拒绝新预约。信用变更同时写通知与 Kafka 事件，供用户查看和统计分析。

## 消息、事件与实时通知

### RabbitMQ 与 Kafka 为什么同时存在

| 组件 | 处理的问题 | 是否参与预约事务 |
| --- | --- | --- |
| RabbitMQ | 预约结果、候补补位、签到/爽约、信用变更和反馈工单等业务动作通知 | 否 |
| Kafka | 记录已经发生的预约、取消、签到、爽约和候补事件，供趋势、热门教室和履约率统计 | 否 |
| SSE | 把已经落库的通知推送给当前在线用户 | 否 |

核心状态变化时，业务表和 Outbox 在同一个 MySQL 事务中写入。事务提交后，定时发布器扫描待发送记录：

1. 通知 Outbox 投递 RabbitMQ。
2. RabbitMQ 消费者用 Outbox 事件 ID 作为通知 ID，实现重复投递幂等。
3. 通知先写入 `tb_notification`，再通过 SSE 推送；用户离线时仍可在通知中心查看。
4. 领域事件 Outbox 投递 Kafka 的 `classroom.events` Topic。
5. `classroom-statistics-consumer` 消费事件并更新 `tb_event_statistics`。
6. 发布失败时记录 `retry_count`、`last_error` 和下一次重试时间，超过阈值进入失败状态；RabbitMQ 另配置死信队列。

前端使用 `EventSource` 订阅 `/notifications/stream`。收到通知后刷新通知中心、未读数、仪表盘和信用分；当前实现不是浏览器系统级弹窗。

## 缓存与搜索

### Caffeine + Redis 二级缓存

项目采用 Cache Aside：

1. 先读取 Caffeine 本地缓存。
2. 未命中时读取 Redis 共享缓存。
3. Redis 仍未命中时查询 MySQL，并依次回填 Redis 和 Caffeine。
4. 教室数据更新后删除两级缓存，后续请求重新加载。

Caffeine 减少单实例到 Redis 的网络访问，Redis 让多个应用实例看到共享的缓存结果。Redis ZSet `rank:room:hot` 独立维护热门教室分数，通过 `ZREVRANGE`/倒序范围查询生成排行榜。剩余名额不使用 Caffeine 作为判断依据，防止本地缓存过期导致错误预约。

### Elasticsearch

`classroom_index` 当前保存教室 ID、楼栋、教室号、容量、类型、设备、状态和更新时间，支持关键词、楼栋、容量、设备和类型组合检索。ES 结果只是候选列表；用户点击预约后仍重新校验 MySQL `room_slot` 和 Redis 名额。

当 ES 未启用或查询异常时，接口降级为 MySQL 查询；管理员可通过 `/rooms/search/sync` 重建索引。

## Agent 与 RAG

### Agent 能做什么

LangChain4j 向模型暴露三个 `@Tool`：

| Tool | 能力 | 安全边界 |
| --- | --- | --- |
| `searchOpenSlots` | 按日期、时间、楼栋、容量和设备查找管理员已开放时段 | 只读，不创建预约 |
| `retrievePolicyKnowledge` | 检索预约规则和上传的校园制度，返回文档及 Chunk 引用 | 只读，外部制度明确标注为参考资料 |
| `getMyReservations` | 查询当前登录用户最近的预约 | 只读，不允许查询其他用户 |

Agent 不直接调用预约、取消、签到、库存修改和管理员写接口。它只能给出候选时段或待确认草稿，真正预约必须由用户确认后进入普通预约事务链路。

### PDF 入库流程

1. 管理员上传 PDF、Markdown 或 TXT，单文件最大 10 MB。
2. PDFBox 提取文本；加密文件、纯扫描件和空文本文件被拒绝，扫描件需要先做 OCR。
3. 清理空白后按约 650 个 Java 字符切分，相邻 Chunk 重叠约 100 个字符，并优先在换行、中文句号和空格处结束。
4. 文档元数据与 Chunk 原文先写 MySQL，便于审计、重建和引用定位。
5. `qwen3.7-text-embedding` 按每批最多 20 条生成 1024 维向量。
6. 向量按每批最多 64 个 Point 写入 Qdrant Collection `classroom_agent_knowledge`，距离函数为 Cosine。
7. 失败文档记录 `PARTIAL` 状态；Qdrant 可从 MySQL Chunk 重新构建。

使用滑动窗口是为了减少关键句跨 Chunk 边界被截断的概率。650/100 是当前语料和调用成本下的工程参数，不宣称是通用最优值。

### 混合检索与精排

```text
Query
  ├─ MySQL Chunk 字符 unigram/bigram 关键词召回
  └─ Qdrant 向量召回
           ↓
      RRF(k=60) 融合
           ↓
      Top 30 候选
           ↓
  qwen3-rerank Cross-Encoder
           ↓
finalScore = 0.35 × rerank + 0.65 × normalizedRRF
           ↓
      Top 3 证据 + 引用
           ↓
       DeepSeek 生成回答
```

关键词分支不是 Elasticsearch BM25，而是最多扫描 400 条当前分类的活跃 Chunk，并在 Java 中计算字符 unigram/bigram 重叠。双路结果使用 RRF 融合，避免直接比较含义不同的关键词分数和余弦分数。Cross-Encoder 联合编码 Query 与候选 Chunk，用于解决“召回到了但排序靠后”的问题。

项目曾验证“只按 Cross-Encoder 分数排序”会把个别正确 Chunk 挤出 Top 3，因此最终在 DEV 集上选择 35% 精排分数与 65% 归一化 RRF 的加权，而不是用精排完全替代粗排。

### 降级与观测

- Rerank 失败：保留 RRF 顺序。
- Embedding 或 Qdrant 失败：退化为关键词检索。
- LLM 失败：返回结构化工具结果或带引用证据，不编造回答。
- Agent 整体不可用：教室查询和预约 REST 主链路仍可使用。
- 每次调用记录模型、工具、检索/精排/生成耗时、Token 数、估算费用、引用及错误信息。
- 同一次规则问答只保存并复用一份检索快照，避免 Tool 与生成阶段重复调用 Embedding 和 Rerank。

## 测试结果

### 热门时段并发压测

单机环境使用 JMeter 模拟 **1000 名用户分 2 批、每批 500 人同步抢约容量为 80 的同一 `room_slot`**。这里的 1000 是总虚拟用户数，稳定同步批次为 500，不表述为“1000 瞬时并发”。

| 指标 | 结果 |
| --- | ---: |
| 预约请求 | 1000 |
| JMeter 业务请求错误率 | 0% |
| 平均响应时间 | 639 ms |
| P95 | 1.255 s |
| P99 | 1.297 s |
| 正式预约 | 80 |
| WAITING 候补 | 920 |
| 重复有效预约 | 0 |
| 名额超额占用 | 0 |

测试后同时核对 `tb_room_slot`、`tb_reserve_order`、`tb_reserve_waitlist` 和 Redis 名额/用户标记，确认 `reserved_count=80`、`available_capacity=0`，有效预约与候补数量之和为 1000。

### RAG 检索评测

评测过程先用 45 条 DEV 定位切分、候选召回与排序问题；已查看结果的 15 条历史 TEST 随后降级为回归集。参数冻结后另建 20 条 BLIND_TEST，记录语料、问题集、Qrels 和配置哈希，再执行首次盲测。

| BLIND_TEST20 流水线 | Recall@1 | Recall@3 | MRR@3 | 平均检索 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 关键词 | 90% | 100% | 95% | 41 ms | 47 ms |
| 向量 | 80% | 95% | 86.67% | 164 ms | 260 ms |
| RRF | 95% | 95% | 95% | 205 ms | 300 ms |
| RRF + Cross-Encoder 加权精排 | **95%** | **100%** | **96.67%** | 485 ms | 571 ms |

`Recall@3` 的目标是人工标注的相关 **Chunk**，它证明正确证据是否进入 Top 3，不等于最终回答正确率。20 条盲测规模仍较小，且来自单份长文档，结果只用于该项目版本的工程验证，不包装成通用 RAG 基准。

较早一轮 65 条端到端答案评测记录了以下指标：引用正确率自动代理 80%、忠实度启发式代理 80%、无依据回答比例 20%、5 条拒答用例准确率 100%；端到端平均 3651 ms、P50 3230 ms、P95 4970 ms，检索平均 788 ms、精排平均 296 ms、生成平均 2504 ms。该轮人工审核尚未完成，因此 **人工正确率保持 N/A**，不把自动指标冒充人工结论。

完整评测方法、失败样本和指标边界见：

- [Agent 与 RAG 面试手册](docs/Agent与RAG面试手册.md)
- [Agent 评测操作手册](docs/Agent评测操作手册.md)
- [Agent 答案评测报告](docs/Agent答案评测报告-20260901.md)
- [RAG 交叉编码器评测](docs/RAG交叉编码器评测-20260830.md)

## 快速启动

### 环境要求

- JDK 17
- Maven 3.9+
- Docker Desktop 与 Docker Compose
- Windows PowerShell 5.1+ 或 PowerShell 7+

### 方式一：Docker 启动中间件，IDEA 启动应用

这是本地开发和面试演示推荐方式。

```powershell
cd ClassroomReservationBoot
docker compose -f docker-compose.middleware.yml up -d
docker compose -f docker-compose.middleware.yml ps
mvn spring-boot:run
```

也可以在 IDEA 中直接运行：

```text
com.xuan.boot.ClassroomReservationBootApplication
```

### 方式二：全部使用 Docker Compose

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
```

未配置外部模型密钥时，核心预约功能、关键词规则检索和确定性 Agent 仍可运行；向量检索、Cross-Encoder 和真实 LLM 会保持关闭或进入降级路径。

### 可选 Agent 环境变量

真实密钥只写入 IDEA Run Configuration、操作系统环境变量或本地 `.env`，不要提交到 Git。

```powershell
$env:CLASSROOM_AGENT_EMBEDDING_ENABLED = "true"
$env:CLASSROOM_AGENT_EMBEDDING_API_KEY = "<your-key>"
$env:CLASSROOM_AGENT_EMBEDDING_BASE_URL = "https://<workspace-id>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
$env:CLASSROOM_AGENT_EMBEDDING_MODEL = "qwen3.7-text-embedding"
$env:CLASSROOM_AGENT_EMBEDDING_DIMENSIONS = "1024"

$env:CLASSROOM_AGENT_VECTOR_ENABLED = "true"
$env:CLASSROOM_QDRANT_URL = "http://localhost:6333"

$env:CLASSROOM_AGENT_RERANK_ENABLED = "true"
$env:CLASSROOM_AGENT_RERANK_MODEL = "qwen3-rerank"

$env:CLASSROOM_AGENT_LANGCHAIN_ENABLED = "true"
$env:CLASSROOM_AGENT_LANGCHAIN_API_KEY = "<your-key>"
$env:CLASSROOM_AGENT_LANGCHAIN_BASE_URL = "https://api.deepseek.com/v1"
$env:CLASSROOM_AGENT_LANGCHAIN_MODEL = "deepseek-chat"
```

项目提供 [.env.example](.env.example)，其中没有真实密钥。

### 访问地址

| 服务 | 地址 | 默认凭据 |
| --- | --- | --- |
| 项目前端 | <http://localhost:8081> | 见下方演示账号 |
| Swagger UI | <http://localhost:8081/swagger-ui.html> | 登录接口公开，其余接口携带 Token |
| Actuator Health | <http://localhost:8081/actuator/health> | 无 |
| Prometheus 指标 | <http://localhost:8081/actuator/prometheus> | 无 |
| RabbitMQ 控制台 | <http://localhost:15672> | `guest / guest` |
| Elasticsearch | <http://localhost:9200> | 无认证的本地开发配置 |
| Qdrant Dashboard | <http://localhost:6333/dashboard> | 无认证的本地开发配置 |
| Prometheus | <http://localhost:9090> | 无 |
| Grafana | <http://localhost:3000> | `admin / admin` |

演示账号由 Flyway `V2__seed_data.sql` 初始化：

```text
管理员：19901541686 / 123456
学生：  17715993804 / 123456
```

演示账号使用 Spring Security Delegating Password Encoder 兼容的 `{noop}` 前缀；通过注册接口创建的新账号使用默认安全编码器生成密码散列。生产部署必须更换演示账号、MySQL/RabbitMQ/Grafana 密码和 `JWT_SECRET`。

## 核心接口

登录后将返回 Token 放入请求头：

```http
X-Token: <login-token>
```

| 模块 | 代表接口 |
| --- | --- |
| 认证 | `POST /auth/register`、`POST /auth/login`、`POST /auth/logout` |
| 教室 | `GET /rooms`、`GET /rooms/{id}`、`POST /rooms`、`PUT /rooms/{id}` |
| 时段管理 | `POST /admin/room-slots`、`POST /admin/room-slots/batch`、`PUT /admin/room-slots/{id}/open|close|maintenance` |
| 开放时段 | `GET /student/room-slots/open` |
| 预约 | `POST /reservations/submit-token`、`POST /reservations`、`POST /reservations/{orderId}/cancel` |
| 候补与签到 | `GET /reservations/waitlist`、`POST /reservations/waitlist/{id}/cancel`、`POST /reservations/sign` |
| 信用分 | `GET /credits/me`、`GET /credits/users/{userId}` |
| 通知 | `GET /notifications`、`GET /notifications/stream`、`POST /notifications/{id}/read` |
| 反馈 | `POST /feedbacks`、`POST /feedbacks/{id}/reply`、`POST /feedbacks/{id}/close` |
| 教室搜索 | `GET /rooms/search`、`POST /rooms/search/sync` |
| Agent | `POST /agent/chat`、`POST /agent/knowledge/upload`、`GET /agent/traces` |
| Agent 评测 | `GET /agent/evaluations/external-policy/diagnostics`、`POST /agent/evaluations/external-policy/answers` |
| 运维观测 | `/ops/redis/**`、`/ops/mq/**`、`/ops/statistics/**`、`/ops/audit/**` |

请求字段和完整响应以 Swagger 为准。

## 测试与验证

### 单元测试和集成测试

```powershell
mvn test
```

当前测试覆盖参数校验、安全编码器、Agent 意图与工具边界、检索诊断、指标上下文、推荐服务和 MQ 运维服务。`InfrastructureTestcontainersTest` 会在 Docker 可用时启动 MySQL、Redis 和 RabbitMQ；Docker 不可用时由 Testcontainers 标记跳过。

### JMeter 热门时段压测

先确保应用与 Docker 中间件已经启动，然后执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\run-load-test.ps1 `
  -InitUsers -ResetSlot `
  -ReserveDate 2026-12-31 -TimeSlot "18:00-20:00" `
  -RoomId 1 -Capacity 80 -Users 1000 -RampSeconds 30 -BarrierSize 500
```

脚本会初始化压测账号、清理同一测试时段的 MySQL/Redis 数据、创建容量 80 的时段、执行压测、生成 JTL/HTML 报告并输出数据库一致性核对结果。重新测试时必须使用未来日期，或修改示例日期。

详细流程见 [手工测试流程](docs/手工测试流程.md) 和 [性能测试报告](docs/性能测试报告.md)。

### Agent/RAG 验证

1. 在 Swagger 登录管理员账号并设置 `X-Token`。
2. 调用 `POST /agent/knowledge/upload` 上传 PDF。
3. 调用 `GET /agent/knowledge/status` 核对 Chunk 数、向量数和索引状态。
4. 调用 `POST /agent/chat` 验证教室检索、规则问答、引用和拒答。
5. 调用 `GET /agent/evaluations/external-policy/diagnostics?split=DEV` 做调试。
6. 参数冻结后才能对新的 `BLIND_TEST` 执行正式评测，已经查看过的测试集只能作为回归集。

具体请求体、人工复核流程和评测指标见 [Agent 评测操作手册](docs/Agent评测操作手册.md)。

## 数据模型

| 表 | 作用 | 关键约束/索引 |
| --- | --- | --- |
| `tb_user` | 用户、角色和账号状态 | 手机号唯一 |
| `tb_classroom` | 教室基础信息 | 楼栋 + 教室号唯一，状态/容量索引 |
| `tb_room_slot` | 教室时段、容量和状态 | 教室 + 日期 + 时间段唯一 |
| `tb_reserve_order` | 学生预约及签到状态 | `active_key` 唯一，用户/教室时间索引 |
| `tb_reserve_waitlist` | FIFO 候补与补位状态 | 用户 + 时段 + 状态唯一，时段/状态/创建时间索引 |
| `tb_checkin` | 签到事实记录 | 预约 ID 唯一 |
| `tb_credit_account` | 当前信用账户 | 用户 ID 主键 |
| `tb_credit_record` | 信用变动流水 | 用户/时间索引 |
| `tb_notification` | 站内通知和已读状态 | 用户/已读/时间索引 |
| `tb_notification_outbox` | RabbitMQ 通知可靠投递 | 状态/下次重试时间索引 |
| `tb_event_outbox` | Kafka 领域事件可靠投递 | `event_id` 唯一 |
| `tb_event_statistics` | Kafka 消费后的统计结果 | 日期 + 类型 + 统计键唯一 |
| `tb_feedback_ticket` | 学生反馈与管理员回复 | 用户、状态和时间索引 |
| `tb_agent_knowledge_document` | 知识文档和索引状态 | 文档状态与分类 |
| `tb_agent_knowledge_chunk` | RAG 原文 Chunk、哈希和向量状态 | 文档 + Chunk 序号唯一 |
| `tb_agent_trace` | Agent 工具、模型、耗时和错误追踪 | 用户/时间、会话索引 |
| `tb_agent_answer_evaluation` | 答案评测与人工复核结果 | 运行 ID、用例 ID |

表结构通过 `src/main/resources/db/migration/V1` 至 `V7` 由 Flyway 管理。MySQL Docker 数据保存在命名卷 `mysql-data`，容器重启不会清空；只有显式执行 `docker compose down -v` 才会删除数据卷。

## 项目结构

```text
ClassroomReservationBoot/
├─ src/main/java/com/xuan/boot/
│  ├─ agent/          # LangChain4j @Tool 与 Agent 配置
│  ├─ config/         # Security、Redis、RabbitMQ、OpenAPI、可观测配置
│  ├─ controller/     # REST 接口
│  ├─ domain/         # 领域对象与状态常量
│  ├─ dto/            # 请求响应模型
│  ├─ mapper/         # MyBatis Mapper
│  ├─ service/        # 业务接口与实现
│  ├─ support/        # JWT、Redis Key、二级缓存、上下文等
│  └─ validation/     # 日期、时间段和手机号校验
├─ src/main/resources/
│  ├─ db/migration/   # Flyway V1-V7
│  └─ static/         # 多页面式前端路由与样式
├─ src/test/          # JUnit、Mockito、Testcontainers
├─ jmeter/            # 压测计划、账号初始化和 PowerShell 执行脚本
├─ docker/            # Prometheus、Grafana 配置
├─ docs/              # 测试、运维、Agent 和面试文档
├─ docker-compose.yml
└─ Dockerfile
```

## 文档导航

- [项目完整面试指南](docs/项目完整面试指南.md)：从业务流程、代码调用顺序到八股追问的完整说明。
- [功能实现与面试指南](docs/功能实现与面试指南.md)：各模块实现方式与面试表达。
- [项目组件架构指南](docs/项目组件架构指南.md)：组件是什么、为什么选、如何协作。
- [Agent 与 RAG 面试手册](docs/Agent与RAG面试手册.md)：切分、Embedding、Qdrant、RRF、精排、降级和评测。
- [手工测试流程](docs/手工测试流程.md)：Swagger、数据库、中间件和 JMeter 测试步骤。
- [前端端到端测试指南](docs/前端端到端测试指南.md)：学生与管理员页面交互检查。
- [简历描述真实性对照表](docs/简历描述真实性对照表.md)：每条简历描述对应的代码和证据。

## 已知边界与后续方向

- 当前是单体应用，适合项目规模和本地演示；拆分微服务会引入分布式事务、链路追踪、部署和运维成本，现阶段收益不足。
- 教师整间教室申请仅有表结构，尚需补角色、申请、审批、冲突处理和前端流程。
- `room_slot` 已有 `EXPIRED` 状态定义，但当前还需补充自动扫描并把已结束时段转为过期状态的调度任务。
- Elasticsearch 文档当前只同步教室基础字段；接口虽然预留日期和时间段参数，但要真正按开放时段检索，还需把 `room_slot` 摘要同步进索引，或先由 ES 查候选教室再用 MySQL 批量过滤可用时段。
- 候补当前按 FIFO；`priority_score` 可在二期接入信用分、等待时长和违约次数，但需要先定义公平性和可解释规则。
- SSE 在线连接保存在单实例内存中；多实例部署需要通过 Redis Pub/Sub、RabbitMQ 或专用推送网关把通知路由到持有连接的实例。
- 二级缓存采用删除失效与 TTL，允许短暂最终一致；预约库存不依赖该缓存。
- RAG 目前主要验证单份长制度文档和有限盲测集，下一步应增加跨文档语料、双人标注、答案人工复核与更大的全新盲测集。
- 生产环境还需要外部化 Secret、TLS、限流、备份恢复、告警规则和多实例故障演练。

## 一分钟项目介绍

> 这是一个以 `room_slot` 为核心的智慧校园教室预约与规则问答平台。管理员先开放具体教室时段，学生预约其中一个名额，满员后进入 FIFO 候补。热门时段预约通过 Submit Token、Redisson、Redis Lua、MySQL 条件更新和唯一索引共同保证名额不被超额占用，并用 Outbox 连接 RabbitMQ 通知和 Kafka 统计事件。通知消费后持久化到 MySQL，再通过 SSE 更新在线用户的通知中心。项目还实现了基于 LangChain4j 的只读预约 Agent，以及 PDFBox 切分、Qdrant 向量检索、RRF 融合和 Cross-Encoder 精排的 RAG 链路。JMeter 在 1000 个总用户、每批 500 人抢 80 个名额的测试中得到 0% 业务错误率，最终 80 条预约、920 条候补，无名额超额占用和重复预约；冻结的 20 条 RAG 盲测中最终 Recall@3 为 100%，同时明确该指标只代表证据召回，不等于答案正确率。

## 安全说明

- 仓库不包含真实模型 API Key、IDEA 本地配置、上传语料、日志和生成的压测报告。
- `.env`、`.idea`、`uploads/`、`test-data/agent-corpus/`、JMeter JTL/HTML 报告等均由 `.gitignore` 排除。
- 如果密钥曾经出现在聊天、截图、日志或提交历史中，应立即在服务商控制台轮换，不能只依赖删除文件。
