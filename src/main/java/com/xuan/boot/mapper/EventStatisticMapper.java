package com.xuan.boot.mapper;

import com.xuan.boot.domain.EventStatistic;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface EventStatisticMapper {
    @Insert("insert into tb_event_statistics(stat_date, stat_type, room_id, room_slot_id, stat_key, stat_value) " +
            "values(#{statDate}, #{statType}, #{roomId}, #{roomSlotId}, #{statKey}, #{delta}) " +
            "on duplicate key update stat_value=stat_value+values(stat_value), " +
            "room_id=values(room_id), room_slot_id=values(room_slot_id), update_time=now()")
    int increment(@Param("statDate") LocalDate statDate,
                  @Param("statType") String statType,
                  @Param("roomId") Long roomId,
                  @Param("roomSlotId") Long roomSlotId,
                  @Param("statKey") String statKey,
                  @Param("delta") BigDecimal delta);

    @Select("select * from tb_event_statistics where stat_date=#{statDate} " +
            "order by stat_type asc, stat_value desc, update_time desc limit #{limit}")
    List<EventStatistic> listByDate(@Param("statDate") LocalDate statDate, @Param("limit") Integer limit);

    @Select("select * from tb_event_statistics where stat_date=#{statDate} and stat_type=#{statType} " +
            "order by stat_value desc, update_time desc limit #{limit}")
    List<EventStatistic> listByType(@Param("statDate") LocalDate statDate,
                                    @Param("statType") String statType,
                                    @Param("limit") Integer limit);
}
