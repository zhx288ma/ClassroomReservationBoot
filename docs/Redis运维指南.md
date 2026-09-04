# Redis 使用与运维说明

你问的“Redis 那边怎么办”很关键。这个项目不是只配了 Redis 地址，代码已经实际使用 Redis；但 Redis 本身不需要像 MySQL 一样手工建表，它是 key-value 存储，key 会在业务运行时自动写入。

## 1. Redis 怎么启动

如果只启动 Redis：

```bash
cd D:/A_project/crs/ClassroomReservationBoot
docker compose up -d redis
```

如果完整启动所有服务：

```bash
docker compose up -d --build
```

Redis 服务地址：

```text
localhost:6379
```

对应配置：

```text
docker-compose.yml:24
src/main/resources/application.yml
```

我已经把 Redis 改成 AOF 持久化，避免演示时重启容器后数据全部丢失：

```text
docker-compose.yml:26
```

## 2. Redis 里有哪些 key

统一定义在：

```text
src/main/java/com/xuan/boot/support/RedisKeys.java:1
```

| Key | 类型 | 作用 | 写入位置 |
| --- | --- | --- | --- |
| `login:token:{token}` | String | 登录态，token -> userId | `AuthServiceImpl.java:60` |
| `cache:classroom:{roomId}` | String | 教室详情缓存 | `ClassroomServiceImpl.java:37` |
| `reserve:stock:{roomId}:{date}:{timeSlot}` | String | 教室时间段 Redis 库存 | `ReservationServiceImpl.java:240` |
| `reserve:users:{date}:{timeSlot}` | Set | 已预约该时间段的用户集合 | `ReservationServiceImpl.java:50` Lua 脚本 |
| `reserve:submit:{userId}:{token}` | String | 一次性提交 token | `ReservationServiceImpl.java:95` |
| `rank:room:hot` | ZSet | 热门教室排行榜 | `ClassroomServiceImpl.java:44`、`ReservationServiceImpl.java:144` |
| `sign:user:{userId}:{yyyy-MM}` | BitMap | 用户月度签到 | `ReservationServiceImpl.java:191` |
| `icr:{bizType}:{yyyyMMdd}` | String | Redis 全局 ID 自增序列 | `RedisIdGeneratorService.java:31` |

## 3. Redis 库存怎么初始化

库存不是手工去 Redis 写的，业务会自动初始化：

```text
ReservationServiceImpl.initStockIfNecessary
src/main/java/com/xuan/boot/service/impl/ReservationServiceImpl.java:237
```

流程：

```text
用户预约某教室某时间段
  -> MySQL tb_room_slot 没有记录就 insert ignore 创建
  -> 查 MySQL available_capacity
  -> Redis setIfAbsent reserve:stock:{roomId}:{date}:{timeSlot}
```

也就是说第一次预约某个时间段时，Redis 库存自动从 MySQL 同步。

## 4. Redis 运维演示接口

为了面试演示和排查，我新增了 Redis 运维接口：

```text
src/main/java/com/xuan/boot/controller/RedisOpsController.java:1
src/main/java/com/xuan/boot/service/impl/RedisOpsServiceImpl.java:1
```

| 接口 | 作用 |
| --- | --- |
| `GET /ops/redis/overview` | 查看 Redis 版本、内存、连接数、项目 key 数量 |
| `GET /ops/redis/stock` | 查看某个教室时间段的 Redis 库存和 MySQL 库存 |
| `POST /ops/redis/stock/sync` | 从 MySQL 同步某个时间段库存到 Redis |
| `GET /ops/redis/hot-rooms` | 查看热门教室排行 |
| `DELETE /ops/redis/demo-keys` | 清理演示 key，不删除登录 token |

这些接口需要登录后带 `X-Token` 访问。

## 5. Redis CLI 怎么看

进入 Redis 容器：

```bash
docker exec -it classroom-redis redis-cli
```

查看 key：

```bash
keys login:token:*
keys cache:classroom:*
keys reserve:stock:*
keys reserve:users:*
keys reserve:submit:*
keys sign:user:*
zrevrange rank:room:hot 0 9 withscores
```

查看某个库存：

```bash
get reserve:stock:1:2026-06-01:18:00-20:00
```

查看某个时间段已预约用户：

```bash
smembers reserve:users:2026-06-01:18:00-20:00
```

查看签到 BitMap 某一天：

```bash
getbit sign:user:2:2026-06 0
```

bit 下标从 0 开始，所以 1 号是 0，2 号是 1。

## 6. Swagger 演示步骤

1. 登录：

```text
POST /auth/login
```

拿到 token 后，后续请求带：

```text
X-Token: {token}
```

2. 查看 Redis 概览：

```text
GET /ops/redis/overview
```

3. 查询教室详情，制造缓存：

```text
GET /rooms/1
```

然后再次访问：

```text
GET /ops/redis/overview
```

你会看到 `classroomCaches` 数量变化。

4. 生成提交 token：

```text
POST /reservations/submit-token
```

5. 预约：

```text
POST /reservations
Header:
X-Submit-Token: {submitToken}
```

请求体：

```json
{
  "roomId": 1,
  "reserveDate": "2026-06-01",
  "timeSlot": "18:00-20:00",
  "joinWaitlist": true
}
```

6. 查看库存：

```text
GET /ops/redis/stock?roomId=1&reserveDate=2026-06-01&timeSlot=18:00-20:00
```

7. 查看热门排行：

```text
GET /ops/redis/hot-rooms
```

## 7. Redis 与 MySQL 不一致怎么办

当前策略：

1. MySQL 是最终数据源。
2. Redis 是高并发预扣库存和快速判断层。
3. 首次预约时 Redis 库存从 MySQL 同步。
4. Redis 扣成功但 MySQL 扣失败时，会回滚 Redis。
5. 取消预约时，会同时回滚 Redis 库存和用户集合。
6. 如果演示中手工改了 MySQL，可以调用 `POST /ops/redis/stock/sync` 把某个时间段库存从 MySQL 重新同步到 Redis。

代码位置：

```text
src/main/java/com/xuan/boot/service/impl/RedisOpsServiceImpl.java:61
```

## 8. 面试说法

```text
Redis 不是只做配置，我在项目里用了多种 Redis 数据结构。

登录态使用 String 保存 login:token:{token} -> userId，并设置 TTL；
教室详情使用 String 做缓存，解决热点教室高频查询；
热门教室使用 ZSet，根据浏览和预约行为增加热度分；
预约库存使用 String 保存 reserve:stock:{roomId}:{date}:{timeSlot}；
一人一约的快速判断使用 Set 记录 reserve:users:{date}:{timeSlot}；
高并发预约时使用 Lua 脚本原子完成库存判断、重复预约判断、扣库存和记录用户；
签到统计使用 BitMap；
全局 ID 使用 Redis INCR。

为了演示和排查，我还补了 /ops/redis 相关接口，可以查看 Redis 概览、库存同步、
热门排行和演示 key 清理。
```

## 9. 常见追问

### Q1：Redis 里需要提前建表吗？

不需要。Redis 是 key-value 存储，key 在业务运行时自动创建。

### Q2：压测前要不要手工初始化库存？

一般不需要，第一次预约会自动初始化。但为了演示稳定，可以先调用：

```text
POST /ops/redis/stock/sync
```

### Q3：Redis 数据丢了怎么办？

当前 Docker Redis 开启了 AOF，能减少重启丢数据风险。但业务上 MySQL 才是最终数据源，Redis 丢失后可以重新从 MySQL 同步库存和缓存。

### Q4：为什么不用 Redis 作为最终库存？

Redis 快，但不是核心业务最终账本。预约单、库存状态、候补记录都必须落 MySQL，Redis 负责抗并发和提升性能。

### Q5：为什么 Redis Lua 还要 MySQL 条件更新？

Lua 解决 Redis 侧并发原子性，MySQL 条件更新解决最终数据一致性。两层防线能避免缓存异常或并发边界导致超卖。

## 10. 当前 Redis 方案的不足

1. 运维接口里为了演示用了 `keys`，生产环境应改为 `scan`，避免阻塞 Redis。
2. Redis 库存同步是按单个时间段做的，后续可以做批量预热。
3. 当前预约单仍同步落 MySQL，后续可以升级为 Redis Stream 异步落库。
4. 当前没有 Canal/binlog 同步，数据库被外部修改后需要手动同步 Redis。
