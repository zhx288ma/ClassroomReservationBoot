# 智慧教室预约与资源调度平台 Spring Boot 升级版

本目录是对原 SSM/JSP 教室预约系统的 Spring Boot 重构升级版，目标是让项目具备 Java 后端面试中更常被追问的工程能力：

```text
Spring Boot
MyBatis
MySQL
Redis
Redisson
RabbitMQ
Swagger / OpenAPI
Spring Boot Actuator
Docker Compose
JMeter
JUnit / Mockito
```

## 核心能力

1. 用户注册登录
   - BCrypt 密码存储
   - Redis Token 登录态
   - 拦截器 + ThreadLocal 用户上下文

2. 教室资源管理
   - 教室查询、详情、新增、编辑
   - Redis 缓存教室详情
   - 热门教室 ZSet 排行

3. 高并发预约
   - `roomId + reserveDate + timeSlot` 时间段库存模型
   - Redis Lua 原子判断库存和重复预约
   - MySQL 条件更新扣减库存
   - `active_key` 唯一索引兜底一人一约
   - 一次性提交 token 防重复点击

4. 候补队列
   - 库存不足时加入候补
   - 取消预约后 Redisson 锁保护补位流程
   - 自动将候补用户转为正式预约

5. 签到统计
   - 签到码核验
   - Redis BitMap 记录用户月度签到

6. 异步通知
   - RabbitMQ 解耦预约主流程和站内信生成
   - 消费者异步写入 `tb_notification`
   - 发布确认、失败重试、死信队列兜底异常消息

7. 智能预约助手
   - 根据日期、时间段、预计人数、教学楼偏好推荐教室
   - 返回推荐分和可解释理由

8. 工程化交付
   - Swagger API 文档
   - Actuator 健康检查和基础指标
   - Docker Compose 一键启动 MySQL/Redis/RabbitMQ/应用
   - JMeter 压测脚本
   - JUnit/Mockito 单元测试
   - 压测报告模板
   - 简历指标填写指南见 `docs/简历指标填写指南.md`
   - 简历指标真实性口径见 `docs/简历描述真实性对照表.md`
   - 手工测试流程见 `docs/手工测试流程.md`

9. Redis 运维演示
   - `/ops/redis/overview` 查看 Redis 概览和项目 key 数量
   - `/ops/redis/stock` 查看某个时间段 Redis/MySQL 库存
   - `/ops/redis/stock/sync` 从 MySQL 同步库存到 Redis
   - 说明文档见 `docs/Redis运维指南.md`

10. RabbitMQ 运维演示
   - `/ops/mq/overview` 查看通知队列、死信队列、积压数和消费者数
   - RabbitMQ Management 控制台可直接查看 exchange、queue、dlq
   - 说明文档见 `docs/消息队列运维指南.md`

11. 服务健康检查
   - `/actuator/health` 查看应用、数据库、Redis、RabbitMQ 健康状态
   - `/actuator/metrics` 携带 `X-Token` 查看基础运行指标

中间件和工程化补齐清单见 `docs/中间件运维检查清单.md`。

## 快速启动

详细启动配置见 `docs/启动配置指南.md`。

```bash
docker compose -f docker-compose.middleware.yml up -d
```

然后在 IDEA 中运行 `ClassroomReservationBootApplication`。

访问：

```text
Swagger: http://localhost:8081/swagger-ui.html
Health: http://localhost:8081/actuator/health
```

默认账号：

```text
管理员: 19901541686 / 123456
普通用户: 17715993804 / 123456
```

## 面试讲法

可以这样概括：

```text
我把原来的 SSM 教室预约 CRUD 项目重构为 Spring Boot REST 服务，
围绕热门教室抢约场景设计了时间段库存模型。高并发预约时先通过
Redis Lua 在缓存侧完成库存和重复预约校验，再通过 MySQL 条件更新
和 active_key 唯一索引做最终一致性兜底。取消预约时使用 Redisson
锁保护状态流转，并优先触发候补补位。通知链路通过 RabbitMQ 异步
落库，并补了发布确认、消费重试和死信队列。教室详情使用 Redis
缓存，热门排行使用 ZSet，签到统计使用 BitMap。同时补充了 Swagger、
Actuator、Docker、JMeter 和单元测试，形成完整的工程化交付。
```
