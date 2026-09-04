package com.xuan.boot.mapper;

import com.xuan.boot.domain.CreditAccount;
import com.xuan.boot.domain.CreditRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface CreditMapper {
    @Insert("insert ignore into tb_credit_account(user_id, credit_score, violation_count) values(#{userId}, 100, 0)")
    int insertAccountIfAbsent(Long userId);

    @Select("select * from tb_credit_account where user_id=#{userId}")
    CreditAccount findAccount(Long userId);

    @Update("update tb_credit_account set credit_score=#{score}, violation_count=violation_count+#{violationDelta}, " +
            "last_change_time=now(), update_time=now() where user_id=#{userId}")
    int updateAccount(@Param("userId") Long userId,
                      @Param("score") Integer score,
                      @Param("violationDelta") Integer violationDelta);

    @Insert("insert into tb_credit_record(id, user_id, reservation_id, change_score, before_score, after_score, reason, remark) " +
            "values(#{id}, #{userId}, #{reservationId}, #{changeScore}, #{beforeScore}, #{afterScore}, #{reason}, #{remark})")
    int insertRecord(CreditRecord record);

    @Select("select * from tb_credit_record where user_id=#{userId} order by create_time desc limit #{limit}")
    List<CreditRecord> listRecords(@Param("userId") Long userId, @Param("limit") Integer limit);
}
