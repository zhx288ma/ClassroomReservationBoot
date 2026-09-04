package com.xuan.boot.mapper;

import com.xuan.boot.domain.FeedbackTicket;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface FeedbackMapper {
    @Insert("insert into tb_feedback_ticket(id, user_id, title, content, status) " +
            "values(#{id}, #{userId}, #{title}, #{content}, #{status})")
    int insert(FeedbackTicket ticket);

    @Select("select f.*, u.username, u.phone, admin.username as replied_by_name from tb_feedback_ticket f " +
            "left join tb_user u on f.user_id = u.id " +
            "left join tb_user admin on f.replied_by = admin.id where f.id=#{id}")
    FeedbackTicket findById(Long id);

    @Select({
            "<script>",
            "select f.*, u.username, u.phone, admin.username as replied_by_name from tb_feedback_ticket f",
            "left join tb_user u on f.user_id = u.id",
            "left join tb_user admin on f.replied_by = admin.id",
            "<where>",
            "  <if test='userId != null'> and f.user_id = #{userId} </if>",
            "  <if test='status != null'> and f.status = #{status} </if>",
            "</where>",
            "order by f.update_time desc, f.create_time desc limit #{limit}",
            "</script>"
    })
    List<FeedbackTicket> list(@Param("userId") Long userId,
                              @Param("status") Integer status,
                              @Param("limit") Integer limit);

    @Update("update tb_feedback_ticket set status=1, admin_reply=#{reply}, replied_by=#{adminId}, " +
            "replied_time=now(), update_time=now() where id=#{id} and status in (0,1)")
    int reply(@Param("id") Long id, @Param("adminId") Long adminId, @Param("reply") String reply);

    @Update("update tb_feedback_ticket set status=2, update_time=now() where id=#{id} and status in (0,1)")
    int close(Long id);
}
