param(
    [string]$HostName = "localhost",
    [int]$Port = 8081,
    [int]$RoomId = 1,
    [string]$ReserveDate = "2026-06-01",
    [string]$TimeSlot = "18:00-20:00",
    [int]$Capacity = 80,
    [int]$Users = 1000,
    [int]$RampSeconds = 10,
    [int]$BarrierSize = 100,
    [switch]$InitUsers,
    [switch]$ResetSlot
)

$ErrorActionPreference = "Stop"

if ($env:JAVA_HOME) {
    $javaBin = Join-Path $env:JAVA_HOME "bin"
    if (Test-Path (Join-Path $javaBin "java.exe")) {
        $env:Path = "$javaBin;$env:Path"
    }
}

if ($env:JMETER_HOME) {
    $jmeterBin = Join-Path $env:JMETER_HOME "bin"
    if (Test-Path (Join-Path $jmeterBin "jmeter.bat")) {
        $env:Path = "$jmeterBin;$env:Path"
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resultFile = Join-Path $PSScriptRoot "result-$stamp.jtl"
$reportDir = Join-Path $PSScriptRoot "report-$stamp"
$testPlan = Join-Path $PSScriptRoot "classroom-reservation-load-test.jmx"
$runtimeTestPlan = Join-Path $env:TEMP "classroom-reservation-load-test-$stamp.jmx"

Set-Location $projectRoot

$healthUrl = "http://$HostName`:$Port/actuator/health"
try {
    $health = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 5
    if ($health.status -ne "UP") {
        throw "health status is $($health.status)"
    }
} catch {
    throw "后端未就绪，无法开始压测。请确认当前项目已启动且 $healthUrl 返回 status=UP。详细原因：$($_.Exception.Message)"
}

if ($InitUsers) {
    Get-Content -Raw (Join-Path $PSScriptRoot "loadtest-users.sql") |
        docker exec -i classroom-mysql mysql -uroot -p123456 classroom_reservation
    if ($LASTEXITCODE -ne 0) {
        throw "压测账号初始化失败，已停止压测。请先修复 loadtest-users.sql 或确认 classroom-mysql 容器状态。"
    }
}

if ($ResetSlot) {
    Write-Host "Reset MySQL slot data: roomId=$RoomId, date=$ReserveDate, timeSlot=$TimeSlot, capacity=$Capacity"
    $resetSql = "SET @roomId=$RoomId; SET @d='$ReserveDate'; SET @t='$TimeSlot'; DELETE FROM tb_reserve_waitlist WHERE room_id=@roomId AND reserve_date=@d AND time_slot=@t; DELETE FROM tb_reserve_order WHERE room_id=@roomId AND reserve_date=@d AND time_slot=@t; DELETE FROM tb_room_slot WHERE room_id=@roomId AND reserve_date=@d AND time_slot=@t; INSERT INTO tb_room_slot(room_id,reserve_date,time_slot,total_capacity,available_capacity,reserved_count,waitlist_count,status,open_type,created_by) VALUES(@roomId,@d,@t,$Capacity,$Capacity,0,0,1,'SELF_STUDY',1); SELECT id, room_id, reserve_date, time_slot, total_capacity, available_capacity, reserved_count, waitlist_count, status FROM tb_room_slot WHERE room_id=@roomId AND reserve_date=@d AND time_slot=@t;"
    docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e $resetSql
    if ($LASTEXITCODE -ne 0) {
        throw "压测时段重置失败，已停止压测。"
    }

    $stockKey = "reserve:stock:{0}:{1}:{2}" -f $RoomId, $ReserveDate, $TimeSlot
    $usersKey = "reserve:users:{0}:{1}:{2}" -f $RoomId, $ReserveDate, $TimeSlot
    $userTimePattern = "reserve:user-time:*:{0}:{1}" -f $ReserveDate, $TimeSlot
    Write-Host "Reset Redis keys: $stockKey, $usersKey, $userTimePattern"
    docker exec classroom-redis redis-cli DEL $stockKey $usersKey | Out-Host
    $userTimeKeys = docker exec classroom-redis redis-cli --raw --scan --pattern $userTimePattern
    $deletedUserTimeKeys = 0
    foreach ($key in $userTimeKeys) {
        if ($key -and $key.Trim().Length -gt 0) {
            docker exec classroom-redis redis-cli DEL $key | Out-Null
            $deletedUserTimeKeys++
        }
    }
    Write-Host "Deleted reserve:user-time keys: $deletedUserTimeKeys"
}

if ($BarrierSize -lt 1 -or $BarrierSize -gt $Users) {
    throw "BarrierSize 必须介于 1 和 Users 之间。当前 Users=$Users, BarrierSize=$BarrierSize"
}
# if ($Users % $BarrierSize -ne 0) {
#     throw "Users 必须是 BarrierSize 的整数倍，否则最后一批线程会等待 SyncTimer 超时并扭曲吞吐量。当前 Users=$Users, BarrierSize=$BarrierSize。示例：300 并发请使用 Users=900 或 1200。"
# }

# SyncTimer 的 groupSize 是 JMeter 的整数属性，不能使用 -J 参数替换；运行前生成本次压测专用计划。
$syncTimerPattern = '<intProp name="groupSize">\d+</intProp>'
$runtimePlanContent = Get-Content -Raw $testPlan
if ($runtimePlanContent -notmatch $syncTimerPattern) {
    throw "无法定位 JMeter SyncTimer 的 groupSize 配置。"
}
$syncTimerReplacement = '<intProp name="groupSize">' + $BarrierSize + '</intProp>'
$runtimePlanContent = [regex]::Replace($runtimePlanContent, $syncTimerPattern, $syncTimerReplacement, 1)
Set-Content -Path $runtimeTestPlan -Value $runtimePlanContent -Encoding UTF8

$jmeterArgs = @(
    "-n",
    "-t", $runtimeTestPlan,
    "-Jhost=$($HostName)",
    "-Jport=$($Port)",
    "-JroomId=$($RoomId)",
    "-JreserveDate=$($ReserveDate)",
    "-JtimeSlot=$($TimeSlot)",
    "-Jusers=$($Users)",
    "-JrampSeconds=$($RampSeconds)",
    "-JbarrierSize=$($BarrierSize)",
    "-l", $resultFile,
    "-e",
    "-o", $reportDir
)

Write-Host "Target: http://$($HostName):$($Port)"
Write-Host "Reserve slot: roomId=$RoomId, date=$ReserveDate, timeSlot=$TimeSlot, capacity=$Capacity, users=$Users, rampSeconds=$RampSeconds, barrierSize=$BarrierSize"

try {
    & jmeter @jmeterArgs
} finally {
    Remove-Item -LiteralPath $runtimeTestPlan -Force -ErrorAction SilentlyContinue
}

Write-Host "JMeter result: $resultFile"
Write-Host "HTML report:  $reportDir\index.html"

Write-Host "MySQL validation for the tested room slot:"
$validationSql = "SELECT id, total_capacity, available_capacity, reserved_count, waitlist_count, status FROM tb_room_slot WHERE room_id=$RoomId AND reserve_date='$ReserveDate' AND time_slot='$TimeSlot'; SELECT status, COUNT(*) c FROM tb_reserve_order WHERE room_id=$RoomId AND reserve_date='$ReserveDate' AND time_slot='$TimeSlot' GROUP BY status; SELECT COUNT(*) duplicate_users FROM (SELECT user_id, COUNT(*) c FROM tb_reserve_order WHERE room_id=$RoomId AND reserve_date='$ReserveDate' AND time_slot='$TimeSlot' AND status IN (0,1,4) GROUP BY user_id HAVING c > 1) t; SELECT status, COUNT(*) c FROM tb_reserve_waitlist WHERE room_id=$RoomId AND reserve_date='$ReserveDate' AND time_slot='$TimeSlot' GROUP BY status;"
docker exec classroom-mysql mysql -uroot -p123456 classroom_reservation -e $validationSql
