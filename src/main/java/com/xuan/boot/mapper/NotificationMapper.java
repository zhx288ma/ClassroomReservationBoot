package com.xuan.boot.mapper;

import com.xuan.boot.domain.Notification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface NotificationMapper {
    @Insert("insert into tb_notification(id, user_id, title, content, read_status) " +
            "values(#{id}, #{userId}, #{title}, #{content}, #{readStatus})")
    int insert(Notification notification);

    @Select("select * from tb_notification where user_id = #{userId} order by create_time desc limit #{limit}")
    List<Notification> listLatestByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);

    @Select("select count(1) from tb_notification where user_id=#{userId} and read_status=0")
    long countUnread(Long userId);

    @Update("update tb_notification set read_status=1, read_time=now() where id=#{id} and user_id=#{userId}")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);
}
