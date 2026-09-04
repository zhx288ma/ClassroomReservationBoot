package com.xuan.boot.service.impl;

import com.xuan.boot.domain.Classroom;
import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.dto.AdvisorRecommendation;
import com.xuan.boot.dto.AdvisorRequest;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.mapper.RoomSlotMapper;
import com.xuan.boot.service.AdvisorService;
import com.xuan.boot.support.RedisKeys;
import com.xuan.boot.support.ReservationTimePolicy;
import com.xuan.boot.support.TwoLevelCacheService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AdvisorServiceImpl implements AdvisorService {
    private final ClassroomMapper classroomMapper;
    private final RoomSlotMapper roomSlotMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final TwoLevelCacheService twoLevelCacheService;

    public AdvisorServiceImpl(ClassroomMapper classroomMapper,
                              RoomSlotMapper roomSlotMapper,
                              StringRedisTemplate stringRedisTemplate,
                              TwoLevelCacheService twoLevelCacheService) {
        this.classroomMapper = classroomMapper;
        this.roomSlotMapper = roomSlotMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.twoLevelCacheService = twoLevelCacheService;
    }

    @Override
    public List<AdvisorRecommendation> recommend(AdvisorRequest request) {
        ReservationTimePolicy.validateReservable(request.getReserveDate(), request.getTimeSlot());
        String key = RedisKeys.ADVISOR_CACHE
                + request.getReserveDate() + ":" + request.getTimeSlot() + ":"
                + request.getExpectedCapacity() + ":" + safe(request.getBuildingName());
        return twoLevelCacheService.getList(key, AdvisorRecommendation.class, Duration.ofMinutes(5),
                () -> doRecommend(request));
    }

    private List<AdvisorRecommendation> doRecommend(AdvisorRequest request) {
        int expectedCapacity = request.getExpectedCapacity() == null ? 1 : request.getExpectedCapacity();
        List<Classroom> classrooms = classroomMapper.search(request.getBuildingName(), null, expectedCapacity, false, 30);
        List<AdvisorRecommendation> result = new ArrayList<>();
        for (Classroom classroom : classrooms) {
            RoomSlot slot = roomSlotMapper.find(classroom.getId(), request.getReserveDate(), request.getTimeSlot());
            boolean available = slot == null || (slot.getStatus() != null && slot.getStatus() == 1
                    && slot.getAvailableCapacity() != null && slot.getAvailableCapacity() > 0);
            Double redisScore = stringRedisTemplate.opsForZSet().score(RedisKeys.HOT_ROOM_RANK, String.valueOf(classroom.getId()));
            long heatScore = redisScore == null ? 0 : redisScore.longValue();
            result.add(buildRecommendation(request, expectedCapacity, classroom, available, heatScore));
        }
        result.sort(Comparator.comparing(AdvisorRecommendation::getMatchScore).reversed()
                .thenComparing(AdvisorRecommendation::getCapacity));
        return result.size() > 10 ? result.subList(0, 10) : result;
    }

    private AdvisorRecommendation buildRecommendation(AdvisorRequest request, int expectedCapacity,
                                                      Classroom classroom, boolean available, long heatScore) {
        int capacityGap = Math.max(0, classroom.getCapacity() - expectedCapacity);
        int score = 50 + (available ? 30 : -45) + Math.max(0, 20 - capacityGap / 5) - Math.min(15, (int) (heatScore / 10));
        score = Math.max(0, Math.min(100, score));
        AdvisorRecommendation recommendation = new AdvisorRecommendation();
        recommendation.setRoomId(classroom.getId());
        recommendation.setBuildingName(classroom.getBuildingName());
        recommendation.setRoomNumber(classroom.getRoomNumber());
        recommendation.setCapacity(classroom.getCapacity());
        recommendation.setAvailable(available);
        recommendation.setHeatScore(heatScore);
        recommendation.setMatchScore(score);
        recommendation.setReason((available ? "该时间段可预约" : "该时间段可能已满，可加入候补")
                + "；容量 " + classroom.getCapacity() + " 人，匹配预计人数 " + expectedCapacity + " 人"
                + "；历史热度 " + heatScore
                + "；推荐分 " + score
                + "；日期 " + request.getReserveDate() + " " + request.getTimeSlot());
        return recommendation;
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "_" : value.trim();
    }
}
