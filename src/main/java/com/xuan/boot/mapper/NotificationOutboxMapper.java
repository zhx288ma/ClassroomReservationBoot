package com.xuan.boot.mapper;

import com.xuan.boot.domain.NotificationOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationOutboxMapper {
    @Insert("insert into tb_notification_outbox(id, user_id, title, content, status, retry_count, next_retry_time) " +
            "values(#{id}, #{userId}, #{title}, #{content}, #{status}, #{retryCount}, #{nextRetryTime})")
    int insert(NotificationOutbox outbox);

    @Select("select * from tb_notification_outbox where status in (0,2) and next_retry_time <= now() " +
            "order by create_time asc limit #{limit}")
    List<NotificationOutbox> findDue(@Param("limit") Integer limit);

    @Select("select * from tb_notification_outbox order by create_time desc limit #{limit}")
    List<NotificationOutbox> listLatest(@Param("limit") Integer limit);

    @Update("update tb_notification_outbox set status=1, last_error=null, update_time=now() where id=#{id} and status in (0,2)")
    int markSent(@Param("id") Long id);

    @Update("update tb_notification_outbox set status=#{status}, retry_count=retry_count+1, " +
            "next_retry_time=#{nextRetryTime}, last_error=#{lastError}, update_time=now() where id=#{id} and status in (0,2)")
    int markRetry(@Param("id") Long id,
                  @Param("status") Integer status,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("lastError") String lastError);

    @Select("select count(1) from tb_notification_outbox where status=#{status}")
    long countByStatus(@Param("status") Integer status);
}
