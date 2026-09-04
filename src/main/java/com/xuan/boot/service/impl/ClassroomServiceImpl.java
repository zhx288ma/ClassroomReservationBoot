package com.xuan.boot.service.impl;

import com.xuan.boot.domain.Classroom;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.service.ClassroomService;
import com.xuan.boot.service.RoomSearchService;
import com.xuan.boot.support.RedisKeys;
import com.xuan.boot.support.TwoLevelCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Random;

@Service
public class ClassroomServiceImpl implements ClassroomService {
    private final ClassroomMapper classroomMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final TwoLevelCacheService twoLevelCacheService;
    private final RoomSearchService roomSearchService;
    private final long cacheTtlMinutes;
    private final Random random = new Random();

    public ClassroomServiceImpl(ClassroomMapper classroomMapper,
                                StringRedisTemplate stringRedisTemplate,
                                TwoLevelCacheService twoLevelCacheService,
                                RoomSearchService roomSearchService,
                                @Value("${reservation.cache-ttl-minutes:30}") long cacheTtlMinutes) {
        this.classroomMapper = classroomMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.twoLevelCacheService = twoLevelCacheService;
        this.roomSearchService = roomSearchService;
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    @Override
    public Classroom detail(Long id) {
        String key = RedisKeys.CLASSROOM_CACHE + id;
        Classroom classroom = twoLevelCacheService.getObject(key, Classroom.class, cacheTtl(), () -> classroomMapper.findById(id));
        if (classroom != null) {
            stringRedisTemplate.opsForZSet().incrementScore(RedisKeys.HOT_ROOM_RANK, String.valueOf(id), 1);
        }
        return classroom;
    }

    @Override
    public List<Classroom> search(String buildingName, String roomType, Integer minCapacity, Boolean includeDisabled, Integer limit) {
        int safeLimit = limit == null ? 50 : limit;
        boolean showDisabled = Boolean.TRUE.equals(includeDisabled);
        String key = RedisKeys.CLASSROOM_SEARCH_CACHE
                + safe(buildingName) + ":" + safe(roomType) + ":" + safe(minCapacity) + ":" + showDisabled + ":" + safeLimit;
        return twoLevelCacheService.getList(key, Classroom.class, cacheTtl(),
                () -> classroomMapper.search(buildingName, roomType, minCapacity, showDisabled, safeLimit));
    }

    @Override
    public Classroom create(Classroom classroom) {
        classroom.setStatus(classroom.getStatus() == null ? 1 : classroom.getStatus());
        classroomMapper.insert(classroom);
        evictClassroomCaches(classroom.getId());
        roomSearchService.indexRoom(classroom);
        return classroom;
    }

    @Override
    public Classroom update(Classroom classroom) {
        if (classroom.getId() == null) {
            throw new IllegalArgumentException("教室 ID 不能为空");
        }
        classroomMapper.update(classroom);
        evictClassroomCaches(classroom.getId());
        Classroom updated = classroomMapper.findById(classroom.getId());
        roomSearchService.indexRoom(updated);
        return updated;
    }

    private void evictClassroomCaches(Long roomId) {
        if (roomId != null) {
            twoLevelCacheService.evict(RedisKeys.CLASSROOM_CACHE + roomId);
        }
        twoLevelCacheService.evictByPrefix(RedisKeys.CLASSROOM_SEARCH_CACHE);
        twoLevelCacheService.evictByPrefix(RedisKeys.ADVISOR_CACHE);
    }

    private Duration cacheTtl() {
        return Duration.ofMinutes(cacheTtlMinutes).plusSeconds(random.nextInt(180));
    }

    private String safe(Object value) {
        return value == null ? "_" : String.valueOf(value).trim();
    }
}
