# JMeter 热门 room_slot 预约压测流程

目标：模拟 `1000` 名用户并发抢约同一个热门 `room_slot` 的 `80` 个名额，验证不超卖、不重复预约，并拿到可写进简历的平均响应时间、P95、P99、QPS。

## 1. 前置检查

进入项目目录：

```powershell
cd D:\A_project\crs\ClassroomReservationBoot
```

确认 Docker 中间件已启动：

```powershell
docker ps
```

至少应看到：

```text
classroom-mysql
classroom-redis
classroom-rabbitmq
```

确认后端已启动：

```powershell
Invoke-WebRequest http://localhost:8081/actuator/health
```

确认 JMeter 可用：

```powershell
jmeter -v
```

如果 JMeter 找不到 Java，可以在当前 PowerShell 临时指定：

```powershell
$env:JAVA_HOME="D:\JDK\jdk25"
$env:JMETER_HOME="D:\java\softwareSpace\apache-jmeter-5.6.3"
$env:Path="$env:JAVA_HOME\bin;$env:JMETER_HOME\bin;$env:Path"
jmeter -v
```

## 2. 一键初始化、清理并压测

推荐直接使用下面这条命令。它会完成三件事：

1. `-InitUsers`：初始化 1000 个压测用户，手机号为 `18800000001` 到 `18800001000`，密码统一为 `123456`。
2. `-ResetSlot`：清理当前压测时段的旧预约、旧候补、旧 room_slot、Redis 库存、Redis 已预约集合、Redis 用户同时间段预约标记。
3. 执行 JMeter 压测：1000 用户抢 `roomId=1, 2026-12-31, 18:00-20:00` 的 80 个名额。

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\run-load-test.ps1 -InitUsers -ResetSlot -ReserveDate 2026-12-31 -TimeSlot "18:00-20:00" -Users 1000 -RampSeconds 30 -RoomId 1 -Capacity 80
```

如果用户已经初始化过，后续复测可以去掉 `-InitUsers`：

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\run-load-test.ps1 -ResetSlot -ReserveDate 2026-12-31 -TimeSlot "18:00-20:00" -Users 1000 -RampSeconds 30 -RoomId 1 -Capacity 80
```

执行完成后，终端会输出类似：

```text
JMeter result: D:\A_project\crs\ClassroomReservationBoot\jmeter\result-20260518-xxxxxx.jtl
HTML report:  D:\A_project\crs\ClassroomReservationBoot\jmeter\report-20260518-xxxxxx\index.html
```

打开 HTML 报告：

```powershell
start .\jmeter\report-20260518-xxxxxx\index.html
```

把 `report-20260518-xxxxxx` 替换成实际目录名。

## 3. 手动清理命令

如果不用 `-ResetSlot`，也可以手动清理。

清理 MySQL 并重建容量 80 的开放时段：

```powershell
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e "SET @roomId=1; SET @d='2026-12-31'; SET @t='18:00-20:00'; DELETE FROM tb_reserve_waitlist WHERE room_id=@roomId AND reserve_date=@d AND time_slot=@t; DELETE FROM tb_reserve_order WHERE room_id=@roomId AND reserve_date=@d AND time_slot=@t; DELETE FROM tb_room_slot WHERE room_id=@roomId AND reserve_date=@d AND time_slot=@t; INSERT INTO tb_room_slot(room_id,reserve_date,time_slot,total_capacity,available_capacity,reserved_count,waitlist_count,status,open_type,created_by) VALUES(@roomId,@d,@t,80,80,0,0,1,'SELF_STUDY',1);"
```

清理 Redis 库存和已预约用户集合：

```powershell
docker exec classroom-redis redis-cli DEL "reserve:stock:1:2026-12-31:18:00-20:00" "reserve:users:1:2026-12-31:18:00-20:00"
```

清理 Redis 用户同时间段预约标记。这个非常重要，否则上一轮压测残留的 `reserve:user-time:*` 会导致用户被误判为已经预约过：

```powershell
docker exec classroom-redis redis-cli --raw --scan --pattern "reserve:user-time:*:2026-12-31:18:00-20:00" | ForEach-Object { if ($_ -and $_.Trim().Length -gt 0) { docker exec classroom-redis redis-cli DEL $_ } }
```

## 4. 压测后验收 SQL

查看 room_slot 库存：

```powershell
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e "SELECT id, total_capacity, available_capacity, reserved_count, waitlist_count, status FROM tb_room_slot WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00';"
```

查看成功预约数：

```powershell
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e "SELECT status, COUNT(*) c FROM tb_reserve_order WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00' GROUP BY status;"
```

查看是否重复预约：

```powershell
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e "SELECT COUNT(*) duplicate_users FROM (SELECT user_id, COUNT(*) c FROM tb_reserve_order WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00' AND status IN (0,1,4) GROUP BY user_id HAVING c > 1) t;"
```

查看真实候补人数：

```powershell
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e "SELECT status, COUNT(*) c FROM tb_reserve_waitlist WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00' GROUP BY status;"
```

合并版：

```powershell
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e "SELECT id, total_capacity, available_capacity, reserved_count, waitlist_count, status FROM tb_room_slot WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00'; SELECT status, COUNT(*) c FROM tb_reserve_order WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00' GROUP BY status; SELECT COUNT(*) duplicate_users FROM (SELECT user_id, COUNT(*) c FROM tb_reserve_order WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00' AND status IN (0,1,4) GROUP BY user_id HAVING c > 1) t; SELECT status, COUNT(*) c FROM tb_reserve_waitlist WHERE room_id=1 AND reserve_date='2026-12-31' AND time_slot='18:00-20:00' GROUP BY status;"
```

## 5. waitlist_count 说明

`tb_room_slot.waitlist_count` 只是展示计数，不再在预约热链路里同步更新。原因是 1000 人并发时，所有候补请求都会同时更新同一条 `room_slot` 行，容易形成热点行死锁。

压测验收时，真实候补人数以 `tb_reserve_waitlist` 为准。需要刷新页面展示计数时，可以在 Swagger 调用管理员接口：

```text
POST /admin/room-slots/reconcile
```

也可以用 SQL 直接重算：

```powershell
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e "UPDATE tb_room_slot s LEFT JOIN (SELECT room_id, reserve_date, time_slot, COUNT(1) active_count FROM tb_reserve_order WHERE status IN (0,1,4) GROUP BY room_id, reserve_date, time_slot) r ON r.room_id=s.room_id AND r.reserve_date=s.reserve_date AND r.time_slot=s.time_slot LEFT JOIN (SELECT room_id, reserve_date, time_slot, COUNT(1) waiting_count FROM tb_reserve_waitlist WHERE status=0 GROUP BY room_id, reserve_date, time_slot) w ON w.room_id=s.room_id AND w.reserve_date=s.reserve_date AND w.time_slot=s.time_slot SET s.reserved_count=COALESCE(r.active_count,0), s.waitlist_count=COALESCE(w.waiting_count,0), s.available_capacity=GREATEST(s.total_capacity-COALESCE(r.active_count,0),0), s.update_time=NOW();"
```

## 6. 合格标准

| 验收项 | 期望 |
| --- | --- |
| 成功预约数 | `reserved_count <= 80`，理想情况等于 `80` |
| 是否超卖 | 不超卖 |
| 库存一致性 | `available_capacity + reserved_count = total_capacity` |
| 重复预约 | `duplicate_users = 0` |
| 真实候补人数 | 以 `tb_reserve_waitlist` 的 `status=0` 数量为准 |
| 系统错误 | 理想情况下 HTTP 5xx 为 `0` |

如果出现 `reserved_count < 80` 且真实候补人数已经很多，说明预约请求在抢满库存之前发生了异常，需要查看后端日志。之前常见原因是同步更新 `waitlist_count` 造成 MySQL 死锁，已改为最终一致计数。

## 7. 提取 JMeter 指标

从 HTML 报告里看：

| 指标 | 报告位置 |
| --- | --- |
| QPS / Throughput | Dashboard / Statistics |
| 平均响应时间 | Statistics 的 Average |
| P95 | Statistics 的 95th pct |
| P99 | Statistics 的 99th pct |
| 错误率 | Statistics 的 Error % |

也可以用 PowerShell 粗略统计预约接口：

```powershell
$jtl = ".\jmeter\result-20260518-105550.jtl"
$rows = Import-Csv $jtl | Where-Object { $_.label -eq "Reserve Hot Room" }
$elapsed = $rows | ForEach-Object { [int]$_.elapsed } | Sort-Object
$count = $elapsed.Count
$avg = [math]::Round(($elapsed | Measure-Object -Average).Average, 2)
$p95 = $elapsed[[math]::Floor($count * 0.95) - 1]
$p99 = $elapsed[[math]::Floor($count * 0.99) - 1]
$success = ($rows | Where-Object { $_.success -eq "true" }).Count
$durationSeconds = ((($rows | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Maximum).Maximum) - (($rows | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum)) / 1000
$qps = [math]::Round($count / $durationSeconds, 2)
"Reserve Hot Room: samples=$count, httpSuccess=$success, avg=${avg}ms, p95=${p95}ms, p99=${p99}ms, qps=$qps"
```

注意：`httpSuccess` 包含预约成功和候补成功，真实成功预约数要以 MySQL 的 `tb_reserve_order` 为准。

## 8. 简历描述模板

压测通过后，把真实数值填入下面模板：

```text
针对热门教室时段抢约场景，使用 JMeter 模拟 1000 名用户并发抢约 80 个名额；
通过 Submit Token 防重复提交、Redis Lua 原子扣减库存、MySQL 条件更新和唯一索引兜底，
最终成功预约数稳定不超过 80，未出现超卖和重复预约；
接口平均响应时间约 X ms，P95 响应时间约 X ms，P99 响应时间约 X ms，QPS 达到 X。
```
# 并发预约压测

`classroom-reservation-load-test.jmx` 会依次执行登录、创建一次性提交令牌和预约。`BarrierSize` 控制有多少用户在预约点同时放行：它不是总用户数。

## 推荐的两组测试

### 1. 稳定性与性能测试

1000 个总用户、每批 100 个用户抢约 80 个名额。该模式更接近日常突发流量，适合记录平均响应时间、P95/P99、吞吐量和错误率。

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\run-load-test.ps1 `
  -InitUsers -ResetSlot -ReserveDate 2026-12-31 -TimeSlot "18:00-20:00" `
  -RoomId 1 -Capacity 80 -Users 1000 -RampSeconds 30 -BarrierSize 100
```

### 2. 极限一致性测试

1000 个用户在预约点同时放行。该模式主要验证无超卖、无重复预约和候补逻辑；单机 Windows 环境可能出现 TCP `Connection refused`，不能把它作为稳定吞吐指标。

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\run-load-test.ps1 `
  -InitUsers -ResetSlot -ReserveDate 2026-12-31 -TimeSlot "18:00-20:00" `
  -RoomId 1 -Capacity 80 -Users 1000 -RampSeconds 30 -BarrierSize 1000
```

`-ResetSlot` 只删除这次指定的教室、日期、时间段下的预约、候补和 Redis 库存数据，然后重建一个新的测试时段。脚本结束时会自动校验 MySQL 库存、正式预约、候补和重复用户。
