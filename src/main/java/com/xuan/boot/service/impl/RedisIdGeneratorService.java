package com.xuan.boot.service.impl;

import com.xuan.boot.service.IdGeneratorService;
import com.xuan.boot.support.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
public class RedisIdGeneratorService implements IdGeneratorService {
    private static final long BEGIN_TIMESTAMP = LocalDateTime.of(2020, 1, 1, 0, 0)
            .toEpochSecond(ZoneOffset.of("+8"));
    private static final int COUNT_BITS = 24;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdGeneratorService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public long nextId(String bizType) {
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.of("+8"));
        long timestamp = now - BEGIN_TIMESTAMP;
        String date = LocalDate.now().format(DATE_FORMATTER);
        Long count = stringRedisTemplate.opsForValue().increment(RedisKeys.ID_COUNTER + bizType + ":" + date);
        return (timestamp << COUNT_BITS) | (count == null ? 0 : count);
    }
}
