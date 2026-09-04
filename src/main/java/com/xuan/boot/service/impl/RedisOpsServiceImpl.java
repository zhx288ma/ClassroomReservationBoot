package com.xuan.boot.service.impl;

import com.xuan.boot.domain.Classroom;
import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.dto.RedisStockSyncRequest;
import com.xuan.boot.mapper.RoomSlotMapper;
import com.xuan.boot.service.RedisOpsService;
import com.xuan.boot.support.RedisKeys;
import com.xuan.boot.support.TwoLevelCacheService;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Service
public class RedisOpsServiceImpl implements RedisOpsService {
    private final StringRedisTemplate stringRedisTemplate;
    private final ClassroomMapper classroomMapper;
    private final RoomSlotMapper roomSlotMapper;
    private final TwoLevelCacheService twoLevelCacheService;

    public RedisOpsServiceImpl(StringRedisTemplate stringRedisTemplate,
                               ClassroomMapper classroomMapper,
                               RoomSlotMapper roomSlotMapper,
                               TwoLevelCacheService twoLevelCacheService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.classroomMapper = classroomMapper;
        this.roomSlotMapper = roomSlotMapper;
        this.twoLevelCacheService = twoLevelCacheService;
    }

    @Override
    public Map<String, Object> overview() {
        return twoLevelCacheService.getMap(RedisKeys.REDIS_OVERVIEW_CACHE, Duration.ofSeconds(10), this::doOverview);
    }

    private Map<String, Object> doOverview() {
        Map<String, Object> result = new HashMap<>();
        Properties info = stringRedisTemplate.execute((RedisConnection connection) -> connection.serverCommands().info());
        Long dbSize = stringRedisTemplate.execute((RedisConnection connection) -> connection.serverCommands().dbSize());
        result.put("dbSize", dbSize);
        result.put("redisVersion", info == null ? null : info.getProperty("redis_version"));
        result.put("usedMemoryHuman", info == null ? null : info.getProperty("used_memory_human"));
        result.put("connectedClients", info == null ? null : info.getProperty("connected_clients"));
        result.put("keySpaces", keySpaces());
        result.put("localCache", twoLevelCacheService.stats());
        return result;
    }

    @Override
    public Map<String, Object> stock(Long roomId, String reserveDate, String timeSlot) {
        LocalDate date = LocalDate.parse(reserveDate);
        String stockKey = stockKey(roomId, date, timeSlot);
        String usersKey = usersKey(roomId, date, timeSlot);
        Map<String, Object> result = new HashMap<>();
        result.put("stockKey", stockKey);
        result.put("redisStock", stringRedisTemplate.opsForValue().get(stockKey));
        result.put("usersKey", usersKey);
        result.put("reservedUsers", stringRedisTemplate.opsForSet().members(usersKey));
        RoomSlot slot = roomSlotMapper.find(roomId, date, timeSlot);
        result.put("mysqlAvailableCapacity", slot == null ? null : slot.getAvailableCapacity());
        result.put("mysqlTotalCapacity", slot == null ? null : slot.getTotalCapacity());
        return result;
    }

    @Override
    public Map<String, Object> syncStock(RedisStockSyncRequest request) {
        RoomSlot slot = roomSlotMapper.find(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        if (slot == null) {
            roomSlotMapper.insertIfAbsent(request.getRoomId(), request.getReserveDate(), request.getTimeSlot(), defaultSlotCapacity(request.getRoomId()));
            slot = roomSlotMapper.find(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        }
        String key = stockKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        stringRedisTemplate.opsForValue().set(key, String.valueOf(slot.getAvailableCapacity()), Duration.ofDays(2));
        return stock(request.getRoomId(), request.getReserveDate().toString(), request.getTimeSlot());
    }

    @Override
    public Map<String, Object> hotRooms(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(RedisKeys.HOT_ROOM_RANK, 0, Math.max(0, limit - 1));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Map<String, Object> item = new HashMap<>();
                item.put("roomId", tuple.getValue());
                item.put("score", tuple.getScore());
                rows.add(item);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("key", RedisKeys.HOT_ROOM_RANK);
        result.put("rows", rows);
        return result;
    }

    @Override
    public void clearDemoKeys() {
        deleteByPattern(RedisKeys.CLASSROOM_CACHE + "*");
        deleteByPattern(RedisKeys.CLASSROOM_SEARCH_CACHE + "*");
        deleteByPattern(RedisKeys.ADVISOR_CACHE + "*");
        deleteByPattern(RedisKeys.REDIS_OVERVIEW_CACHE);
        deleteByPattern(RedisKeys.RESERVE_STOCK + "*");
        deleteByPattern(RedisKeys.RESERVE_USERS + "*");
        deleteByPattern(RedisKeys.RESERVE_USER_TIME + "*");
        deleteByPattern(RedisKeys.SUBMIT_TOKEN + "*");
        deleteByPattern(RedisKeys.HOT_ROOM_RANK);
        deleteByPattern(RedisKeys.USER_SIGN + "*");
        deleteByPattern(RedisKeys.ID_COUNTER + "*");
        twoLevelCacheService.evictByPrefix(RedisKeys.CLASSROOM_CACHE);
        twoLevelCacheService.evictByPrefix(RedisKeys.CLASSROOM_SEARCH_CACHE);
        twoLevelCacheService.evictByPrefix(RedisKeys.ADVISOR_CACHE);
        twoLevelCacheService.evict(RedisKeys.REDIS_OVERVIEW_CACHE);
    }

    private Map<String, Long> keySpaces() {
        Map<String, Long> result = new HashMap<>();
        result.put("loginTokens", count(RedisKeys.LOGIN_TOKEN + "*"));
        result.put("classroomCaches", count(RedisKeys.CLASSROOM_CACHE + "*"));
        result.put("classroomSearchCaches", count(RedisKeys.CLASSROOM_SEARCH_CACHE + "*"));
        result.put("advisorCaches", count(RedisKeys.ADVISOR_CACHE + "*"));
        result.put("reserveStocks", count(RedisKeys.RESERVE_STOCK + "*"));
        result.put("reserveUserSets", count(RedisKeys.RESERVE_USERS + "*"));
        result.put("reserveUserTimeMarks", count(RedisKeys.RESERVE_USER_TIME + "*"));
        result.put("submitTokens", count(RedisKeys.SUBMIT_TOKEN + "*"));
        result.put("signBitmaps", count(RedisKeys.USER_SIGN + "*"));
        return result;
    }

    private long count(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        return keys == null ? 0 : keys.size();
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private String stockKey(Long roomId, LocalDate reserveDate, String timeSlot) {
        return RedisKeys.RESERVE_STOCK + roomId + ":" + reserveDate + ":" + timeSlot;
    }

    private String usersKey(Long roomId, LocalDate reserveDate, String timeSlot) {
        return RedisKeys.RESERVE_USERS + roomId + ":" + reserveDate + ":" + timeSlot;
    }

    private int defaultSlotCapacity(Long roomId) {
        Classroom classroom = classroomMapper.findById(roomId);
        if (classroom != null && classroom.getCapacity() != null && classroom.getCapacity() > 0) {
            return classroom.getCapacity();
        }
        return 1;
    }
}
