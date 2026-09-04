package com.xuan.boot.mapper;

import com.xuan.boot.domain.WaitlistOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface WaitlistMapper {
    @Insert("insert into tb_reserve_waitlist(id, room_id, room_slot_id, user_id, reserve_date, time_slot, status) " +
            "values(#{id}, #{roomId}, #{roomSlotId}, #{userId}, #{reserveDate}, #{timeSlot}, #{status})")
    int insert(WaitlistOrder order);

    @Insert("insert ignore into tb_reserve_waitlist(id, room_id, room_slot_id, user_id, reserve_date, time_slot, status) " +
            "values(#{id}, #{roomId}, #{roomSlotId}, #{userId}, #{reserveDate}, #{timeSlot}, #{status})")
    int insertIgnore(WaitlistOrder order);

    @Select("select * from tb_reserve_waitlist where user_id=#{userId} and room_id=#{roomId} " +
            "and reserve_date=#{reserveDate} and time_slot=#{timeSlot} and status=0 limit 1")
    WaitlistOrder findWaiting(@Param("userId") Long userId,
                              @Param("roomId") Long roomId,
                              @Param("reserveDate") LocalDate reserveDate,
                              @Param("timeSlot") String timeSlot);

    @Select("select count(1) from tb_reserve_waitlist where user_id=#{userId} and room_id=#{roomId} " +
            "and reserve_date=#{reserveDate} and time_slot=#{timeSlot} and status=0")
    int countWaiting(@Param("userId") Long userId,
                     @Param("roomId") Long roomId,
                     @Param("reserveDate") LocalDate reserveDate,
                     @Param("timeSlot") String timeSlot);

    @Select("select count(1) from tb_reserve_waitlist where room_id=#{roomId} " +
            "and reserve_date=#{reserveDate} and time_slot=#{timeSlot} and status=0")
    int countWaitingByRoomTime(@Param("roomId") Long roomId,
                               @Param("reserveDate") LocalDate reserveDate,
                               @Param("timeSlot") String timeSlot);

    @Select("select * from tb_reserve_waitlist where room_id=#{roomId} and reserve_date=#{reserveDate} " +
            "and time_slot=#{timeSlot} and status=0 order by create_time asc, id asc limit 1")
    WaitlistOrder firstWaiting(@Param("roomId") Long roomId,
                               @Param("reserveDate") LocalDate reserveDate,
                               @Param("timeSlot") String timeSlot);

    @Select("select w.*, c.building_name, c.room_number, c.capacity from tb_reserve_waitlist w " +
            "left join tb_classroom c on w.room_id = c.id where w.id=#{id}")
    WaitlistOrder findById(Long id);

    @Select({
            "<script>",
            "select w.*, c.building_name, c.room_number, c.capacity from tb_reserve_waitlist w",
            "left join tb_classroom c on w.room_id = c.id",
            "<where>",
            "  <if test='userId != null'> and w.user_id = #{userId} </if>",
            "  <if test='roomId != null'> and w.room_id = #{roomId} </if>",
            "  <if test='reserveDate != null'> and w.reserve_date = #{reserveDate} </if>",
            "  <if test='status != null'> and w.status = #{status} </if>",
            "</where>",
            "order by w.create_time desc limit #{limit}",
            "</script>"
    })
    List<WaitlistOrder> list(@Param("userId") Long userId,
                             @Param("roomId") Long roomId,
                             @Param("reserveDate") LocalDate reserveDate,
                             @Param("status") Integer status,
                             @Param("limit") Integer limit);

    @Select("select * from tb_reserve_waitlist where status=0 and " +
            "(reserve_date < #{today} or (reserve_date = #{today} and substring_index(time_slot, '-', 1) <= #{currentTime})) " +
            "order by reserve_date asc, time_slot asc, create_time asc limit #{limit}")
    List<WaitlistOrder> listExpiredCandidates(@Param("today") LocalDate today,
                                              @Param("currentTime") String currentTime,
                                              @Param("limit") Integer limit);

    @Update("update tb_reserve_waitlist set status=#{newStatus}, update_time=now() where id=#{id} and status=#{oldStatus}")
    int updateStatus(@Param("id") Long id, @Param("oldStatus") Integer oldStatus, @Param("newStatus") Integer newStatus);

    @Update("update tb_reserve_waitlist set status=2, update_time=now() where id=#{id} and user_id=#{userId} and status=0")
    int cancelByUser(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update tb_reserve_waitlist set status=2, update_time=now() where id=#{id} and status=0")
    int cancelByAdmin(@Param("id") Long id);

    @Select("select count(1) from tb_reserve_waitlist where status=0")
    long waitingCount();

    @Select("select count(1) from tb_reserve_waitlist where user_id=#{userId} and status=0")
    long waitingCountByUser(Long userId);
}
