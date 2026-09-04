package com.xuan.boot.mapper;

import com.xuan.boot.domain.Classroom;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ClassroomMapper {
    @Select("select * from tb_classroom where id = #{id}")
    Classroom findById(Long id);

    @Select({
            "<script>",
            "select * from tb_classroom",
            "<where>",
            "  <if test='includeDisabled == null or includeDisabled == false'>",
            "    status = 1",
            "  </if>",
            "  <if test='buildingName != null and buildingName != \"\"'>",
            "    and building_name like concat('%', #{buildingName}, '%')",
            "  </if>",
            "  <if test='roomType != null and roomType != \"\"'>",
            "    and room_type = #{roomType}",
            "  </if>",
            "  <if test='minCapacity != null'>",
            "    and capacity &gt;= #{minCapacity}",
            "  </if>",
            "</where>",
            "order by status desc, capacity asc limit #{limit}",
            "</script>"
    })
    List<Classroom> search(@Param("buildingName") String buildingName,
                           @Param("roomType") String roomType,
                           @Param("minCapacity") Integer minCapacity,
                           @Param("includeDisabled") Boolean includeDisabled,
                           @Param("limit") Integer limit);

    @Insert("insert into tb_classroom(building_name, room_number, capacity, room_type, equipment, status) " +
            "values(#{buildingName}, #{roomNumber}, #{capacity}, #{roomType}, #{equipment}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Classroom classroom);

    @Update("update tb_classroom set building_name=#{buildingName}, room_number=#{roomNumber}, capacity=#{capacity}, " +
            "room_type=#{roomType}, equipment=#{equipment}, status=#{status}, update_time=now() where id=#{id}")
    int update(Classroom classroom);
}
