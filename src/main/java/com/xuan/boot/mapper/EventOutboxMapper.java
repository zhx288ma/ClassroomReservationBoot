package com.xuan.boot.mapper;

import com.xuan.boot.domain.EventOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface EventOutboxMapper {
    @Insert("insert into tb_event_outbox(id, event_id, event_type, target_type, aggregate_type, aggregate_id, " +
            "user_id, room_id, room_slot_id, payload, status, retry_count, next_retry_time) " +
            "values(#{id}, #{eventId}, #{eventType}, #{targetType}, #{aggregateType}, #{aggregateId}, " +
            "#{userId}, #{roomId}, #{roomSlotId}, #{payload}, #{status}, #{retryCount}, #{nextRetryTime})")
    int insert(EventOutbox outbox);

    @Select("select * from tb_event_outbox where status in (0,2) and next_retry_time <= now() " +
            "order by create_time asc limit #{limit}")
    List<EventOutbox> findDue(Integer limit);

    @Select("select * from tb_event_outbox order by create_time desc limit #{limit}")
    List<EventOutbox> listLatest(Integer limit);

    @Update("update tb_event_outbox set status=1, update_time=now(), last_error=null where id=#{id}")
    int markSent(Long id);

    @Update("update tb_event_outbox set status=#{status}, retry_count=retry_count+1, " +
            "next_retry_time=#{nextRetryTime}, last_error=#{lastError}, update_time=now() where id=#{id}")
    int markRetry(@Param("id") Long id,
                  @Param("status") Integer status,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("lastError") String lastError);

    @Select("select count(1) from tb_event_outbox where status=#{status}")
    long countByStatus(Integer status);
}
