package com.xuan.boot.mapper;

import com.xuan.boot.domain.AuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AuditLogMapper {
    @Insert("insert into tb_audit_log(trace_id, user_id, role, http_method, uri, http_status, success, latency_ms, client_ip, error_msg) " +
            "values(#{traceId}, #{userId}, #{role}, #{httpMethod}, #{uri}, #{httpStatus}, #{success}, #{latencyMs}, #{clientIp}, #{errorMsg})")
    int insert(AuditLog auditLog);

    @Select("select * from tb_audit_log order by create_time desc limit #{limit}")
    List<AuditLog> listLatest(@Param("limit") Integer limit);

    @Select("select count(1) from tb_audit_log where success=0")
    long countFailures();

    @Select("select count(1) from tb_audit_log where create_time >= date_sub(now(), interval 5 minute)")
    long countRecentRequests();

    @Select("select coalesce(avg(latency_ms), 0) from tb_audit_log where create_time >= date_sub(now(), interval 5 minute)")
    double avgLatencyLastFiveMinutes();
}
