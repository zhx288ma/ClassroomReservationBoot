# Kafka / ES / SSE / room_slot 前端测试流程

## 1. 本地开关

默认 IDEA 启动时：

- `reservation.kafka.enabled=false`：不强制连接 Kafka，事件 outbox 会直接走本地统计落库，方便单机演示。
- `reservation.elasticsearch.enabled=false`：搜索接口会回退到 MySQL 查询，避免没启动 ES 时项目失败。

如果要真实测试 Kafka + ES，先启动中间件：

```powershell
docker compose up -d mysql redis rabbitmq kafka elasticsearch prometheus grafana
```

然后在 IDEA 启动配置里加环境变量：

```text
CLASSROOM_KAFKA_ENABLED=true
KAFKA_BOOTSTRAP_SERVERS=localhost:19092
CLASSROOM_ES_ENABLED=true
ELASTICSEARCH_URL=http://localhost:9200
```

## 2. 管理员 room_slot 页面

1. 登录管理员账号。
2. 点击左侧“时段管理”。
3. 选择教室、开始日期、时间段、容量，状态选 `OPEN`。
4. 点击“创建单个时段”。
5. 期望：右侧 `room_slot` 列表出现记录，状态为“开放”。
6. 点击“关闭”或“维护”。
7. 期望：如果该时段没有学生预约，状态可变更；如果已有预约，后端会拒绝直接关闭或维护。

关联接口：

- `POST /admin/room-slots`
- `POST /admin/room-slots/batch`
- `PUT /admin/room-slots/{id}/open`
- `PUT /admin/room-slots/{id}/close`
- `PUT /admin/room-slots/{id}/maintenance`
- `GET /admin/room-slots`

## 3. Kafka 事件与统计落库

1. 管理员创建一个 `OPEN room_slot`。
2. 学生预约该时段。
3. 管理员进入“统计大屏”并点击“刷新统计”。
4. 期望：`Kafka / Event Outbox` 中 `sent` 增加，`统计落库结果` 中出现 `RESERVATION_SUCCESS`、`ROOM_SLOT_OPENED` 等事件计数。

关联接口：

- `GET /ops/statistics/overview`
- `GET /ops/statistics/dashboard`
- `GET /ops/statistics/console`
- `GET /ops/statistics/outbox`

关联表：

- `tb_event_outbox`
- `tb_event_statistics`

## 4. Elasticsearch 搜索

1. 启用 `CLASSROOM_ES_ENABLED=true` 后启动项目。
2. 管理员在 Swagger 执行 `POST /rooms/search/sync`。
3. 执行 `GET /rooms/search?keyword=计算机&minCapacity=30`。
4. 期望：返回匹配教室。
5. 如果未启用 ES，接口仍可返回 MySQL 兜底查询结果。

## 5. SSE 实时推送

1. 学生登录前端。
2. 保持页面在线。
3. 触发预约成功、取消预约、签到、管理员回复反馈等会产生通知的动作。
4. 期望：页面联调日志出现“收到实时通知”，通知中心和仪表盘自动刷新。

关联接口：

- `GET /notifications/stream?token=登录token`
- `GET /notifications/unread-count`
- `POST /notifications/{id}/read`

## 6. 信用分前端展示

1. 学生登录后进入“控制台”。
2. 期望：能看到“信用分账户”卡片，展示当前信用分和违约次数。
3. 完成签到后刷新，期望信用分记录出现 `CHECKIN_SUCCESS`。
4. 超过签到窗口未签到，定时任务执行后刷新，期望信用分记录出现 `NO_SHOW` 扣分。

关联接口：

- `GET /credits/me`
