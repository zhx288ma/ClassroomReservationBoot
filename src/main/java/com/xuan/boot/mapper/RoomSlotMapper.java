package com.xuan.boot.mapper;

import com.xuan.boot.domain.RoomSlot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface RoomSlotMapper {
    @Select("select * from tb_room_slot where room_id=#{roomId} and reserve_date=#{reserveDate} and time_slot=#{timeSlot}")
    RoomSlot find(@Param("roomId") Long roomId,
                  @Param("reserveDate") LocalDate reserveDate,
                  @Param("timeSlot") String timeSlot);

    @Select("select * from tb_room_slot where id=#{id}")
    RoomSlot findById(Long id);

    @Insert("insert ignore into tb_room_slot(room_id, reserve_date, time_slot, total_capacity, available_capacity, status) " +
            "values(#{roomId}, #{reserveDate}, #{timeSlot}, #{capacity}, #{capacity}, 1)")
    int insertIfAbsent(@Param("roomId") Long roomId,
                       @Param("reserveDate") LocalDate reserveDate,
                       @Param("timeSlot") String timeSlot,
                       @Param("capacity") Integer capacity);

    @Insert("insert ignore into tb_room_slot(room_id, reserve_date, time_slot, total_capacity, available_capacity, status, open_type, created_by) " +
            "values(#{roomId}, #{reserveDate}, #{timeSlot}, #{capacity}, #{capacity}, #{status}, #{openType}, #{createdBy})")
    int insertManaged(@Param("roomId") Long roomId,
                      @Param("reserveDate") LocalDate reserveDate,
                      @Param("timeSlot") String timeSlot,
                      @Param("capacity") Integer capacity,
                      @Param("status") Integer status,
                      @Param("openType") String openType,
                      @Param("createdBy") Long createdBy);

    @Update("update tb_room_slot set available_capacity=available_capacity-1, reserved_count=reserved_count+1, version=version+1, update_time=now() " +
            "where room_id=#{roomId} and reserve_date=#{reserveDate} and time_slot=#{timeSlot} " +
            "and status=1 and available_capacity>0")
    int decreaseStock(@Param("roomId") Long roomId,
                      @Param("reserveDate") LocalDate reserveDate,
                      @Param("timeSlot") String timeSlot);

    @Update("update tb_room_slot set available_capacity=available_capacity-1, reserved_count=reserved_count+1, version=version+1, update_time=now() " +
            "where id=#{slotId} and status=1 and available_capacity>0")
    int decreaseStockById(@Param("slotId") Long slotId);

    @Update("update tb_room_slot set available_capacity=available_capacity+1, reserved_count=greatest(reserved_count-1,0), version=version+1, update_time=now() " +
            "where room_id=#{roomId} and reserve_date=#{reserveDate} and time_slot=#{timeSlot} " +
            "and available_capacity < total_capacity")
    int increaseStock(@Param("roomId") Long roomId,
                      @Param("reserveDate") LocalDate reserveDate,
                      @Param("timeSlot") String timeSlot);

    @Update("update tb_room_slot set status=#{status}, update_time=now() where id=#{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    @Delete("delete from tb_room_slot where id=#{id}")
    int deleteById(Long id);

    @Update({
            "update tb_room_slot s",
            "left join (",
            "  select room_id, reserve_date, time_slot, count(1) active_count",
            "  from tb_reserve_order",
            "  where status in (0, 1, 4)",
            "  group by room_id, reserve_date, time_slot",
            ") r on r.room_id = s.room_id and r.reserve_date = s.reserve_date and r.time_slot = s.time_slot",
            "left join (",
            "  select room_id, reserve_date, time_slot, count(1) waiting_count",
            "  from tb_reserve_waitlist",
            "  where status = 0",
            "  group by room_id, reserve_date, time_slot",
            ") w on w.room_id = s.room_id and w.reserve_date = s.reserve_date and w.time_slot = s.time_slot",
            "set s.reserved_count = coalesce(r.active_count, 0),",
            "    s.waitlist_count = coalesce(w.waiting_count, 0),",
            "    s.available_capacity = greatest(s.total_capacity - coalesce(r.active_count, 0), 0),",
            "    s.update_time = now()"
    })
    int reconcileCounters();

    @Select({
            "<script>",
            "select * from tb_room_slot",
            "<where>",
            "  <if test='roomId != null'> and room_id = #{roomId} </if>",
            "  <if test='reserveDate != null'> and reserve_date = #{reserveDate} </if>",
            "  <if test='status != null'> and status = #{status} </if>",
            "</where>",
            "order by reserve_date desc, time_slot asc, id desc limit #{limit}",
            "</script>"
    })
    List<RoomSlot> list(@Param("roomId") Long roomId,
                        @Param("reserveDate") LocalDate reserveDate,
                        @Param("status") Integer status,
                        @Param("limit") Integer limit);

    @Select("select * from tb_room_slot where status=1 and reserve_date>=#{today} order by reserve_date asc, time_slot asc limit #{limit}")
    List<RoomSlot> listOpen(@Param("today") LocalDate today, @Param("limit") Integer limit);

    @Select({
            "select s.* from tb_room_slot s",
            "where s.status = 1",
            "  and s.available_capacity > 0",
            "  and exists (",
            "    select 1 from tb_reserve_waitlist w",
            "    where w.room_id = s.room_id",
            "      and w.reserve_date = s.reserve_date",
            "      and w.time_slot = s.time_slot",
            "      and w.status = 0",
            "  )",
            "order by s.reserve_date asc, s.time_slot asc, s.id asc limit #{limit}"
    })
    List<RoomSlot> listPromotableWaitlistSlots(@Param("limit") Integer limit);
}
