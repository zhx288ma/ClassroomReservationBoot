package com.xuan.boot;

import com.xuan.boot.domain.Classroom;
import com.xuan.boot.dto.AdvisorRecommendation;
import com.xuan.boot.dto.AdvisorRequest;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.mapper.RoomSlotMapper;
import com.xuan.boot.service.impl.AdvisorServiceImpl;
import com.xuan.boot.support.TwoLevelCacheService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

@ExtendWith(MockitoExtension.class)
class AdvisorServiceImplTest {
    @Test
    void recommendShouldReturnExplainableScore() {
        ClassroomMapper classroomMapper = Mockito.mock(ClassroomMapper.class);
        RoomSlotMapper roomSlotMapper = Mockito.mock(RoomSlotMapper.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOperations = Mockito.mock(ZSetOperations.class);
        TwoLevelCacheService twoLevelCacheService = Mockito.mock(TwoLevelCacheService.class);

        Classroom classroom = new Classroom();
        classroom.setId(1L);
        classroom.setBuildingName("计算机楼");
        classroom.setRoomNumber("106");
        classroom.setCapacity(180);

        LocalDate reserveDate = LocalDate.now().plusDays(2);
        Mockito.when(classroomMapper.search("计算机楼", null, 30, false, 30)).thenReturn(Collections.singletonList(classroom));
        Mockito.when(roomSlotMapper.find(1L, reserveDate, "18:00-20:00")).thenReturn(null);
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        Mockito.when(zSetOperations.score("rank:room:hot", "1")).thenReturn(10.0);
        Mockito.when(twoLevelCacheService.getList(Mockito.anyString(), Mockito.eq(AdvisorRecommendation.class),
                Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            Supplier<List<AdvisorRecommendation>> loader = invocation.getArgument(3);
            return loader.get();
        });

        AdvisorServiceImpl service = new AdvisorServiceImpl(classroomMapper, roomSlotMapper, redisTemplate, twoLevelCacheService);
        AdvisorRequest request = new AdvisorRequest();
        request.setBuildingName("计算机楼");
        request.setExpectedCapacity(30);
        request.setReserveDate(reserveDate);
        request.setTimeSlot("18:00-20:00");

        List<AdvisorRecommendation> recommendations = service.recommend(request);

        Assertions.assertEquals(1, recommendations.size());
        Assertions.assertTrue(recommendations.get(0).isAvailable());
        Assertions.assertTrue(recommendations.get(0).getMatchScore() > 0);
        Assertions.assertTrue(recommendations.get(0).getReason().contains("推荐分"));
    }
}
