package com.xuan.boot.mapper;

import com.xuan.boot.domain.ReservationOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface ReservationOrderMapper {
    @Insert("insert into tb_reserve_order(id, room_id, room_slot_id, user_id, reserve_date, time_slot, active_key, status, sign_code, remark, checkin_deadline) " +
            "values(#{id}, #{roomId}, #{roomSlotId}, #{userId}, #{reserveDate}, #{timeSlot}, #{activeKey}, #{status}, #{signCode}, #{remark}, #{checkinDeadline})")
    int insert(ReservationOrder order);

    @Select("select o.*, c.building_name, c.room_number, c.capacity from tb_reserve_order o " +
            "left join tb_classroom c on o.room_id = c.id where o.id=#{id}")
    ReservationOrder findById(Long id);

    @Select("select count(1) from tb_reserve_order where user_id=#{userId} and reserve_date=#{reserveDate} " +
            "and time_slot=#{timeSlot} and status in (0,1,4)")
    int countActiveByUser(@Param("userId") Long userId,
                          @Param("reserveDate") LocalDate reserveDate,
                          @Param("timeSlot") String timeSlot);

    @Select("select count(1) from tb_reserve_order where room_id=#{roomId} and reserve_date=#{reserveDate} " +
            "and time_slot=#{timeSlot} and status in (0,1,4)")
    int countActiveByRoomTime(@Param("roomId") Long roomId,
                              @Param("reserveDate") LocalDate reserveDate,
                              @Param("timeSlot") String timeSlot);

    @Select({
            "<script>",
            "select o.*, c.building_name, c.room_number, c.capacity from tb_reserve_order o",
            "left join tb_classroom c on o.room_id = c.id",
            "<where>",
            "  <if test='userId != null'> and o.user_id = #{userId} </if>",
            "  <if test='roomId != null'> and o.room_id = #{roomId} </if>",
            "  <if test='reserveDate != null'> and o.reserve_date = #{reserveDate} </if>",
            "  <if test='status != null'> and o.status = #{status} </if>",
            "</where>",
            "order by o.create_time desc limit #{limit}",
            "</script>"
    })
    List<ReservationOrder> list(@Param("userId") Long userId,
                                @Param("roomId") Long roomId,
                                @Param("reserveDate") LocalDate reserveDate,
                                @Param("status") Integer status,
                                @Param("limit") Integer limit);

    @Update("update tb_reserve_order set status=3, active_key=null, cancelled_at=now(), remark='用户取消', version=version+1, update_time=now() " +
            "where id=#{orderId} and user_id=#{userId} and status in (0,1)")
    int cancelByUser(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Update("update tb_reserve_order set status=3, active_key=null, cancelled_at=now(), remark='管理员取消', version=version+1, update_time=now() " +
            "where id=#{orderId} and status in (0,1,4)")
    int cancelByAdmin(@Param("orderId") Long orderId);

    @Update("update tb_reserve_order set status=4, remark='已签到', version=version+1, update_time=now() " +
            "where id=#{orderId} and user_id=#{userId} and sign_code=#{signCode} and status=1")
    int sign(@Param("orderId") Long orderId, @Param("userId") Long userId, @Param("signCode") String signCode);

    @Update("update tb_reserve_order set status=5, active_key=null, remark='未签到', version=version+1, update_time=now() " +
            "where id=#{orderId} and status=1")
    int markNoShow(Long orderId);

    @Select("select * from tb_reserve_order where status=1 and reserve_date<=#{today} order by reserve_date asc, time_slot asc limit #{limit}")
    List<ReservationOrder> listReservedCandidates(@Param("today") LocalDate today, @Param("limit") Integer limit);

    @Select("select count(1) from tb_reserve_order where date(create_time)=curdate()")
    long todayCount();

    @Select("select count(1) from tb_reserve_order where user_id=#{userId} and date(create_time)=curdate()")
    long todayCountByUser(Long userId);

    @Select("select count(1) from tb_reserve_order where status=#{status}")
    long countByStatus(Integer status);

    @Select("select count(1) from tb_reserve_order where user_id=#{userId} and status=#{status}")
    long countByStatusAndUser(@Param("status") Integer status, @Param("userId") Long userId);
}
