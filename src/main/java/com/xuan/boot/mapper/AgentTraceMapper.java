package com.xuan.boot.mapper;

import com.xuan.boot.domain.AgentTrace;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentTraceMapper {
    @Insert("insert into tb_agent_trace(trace_id, user_id, session_id, intent, mode, input_summary, tool_trace_json, source_ids, success, duration_ms, model_names, model_calls_json, retrieval_ms, rerank_ms, generation_ms, input_tokens, output_tokens, total_tokens, estimated_cost, cost_currency, error_message) values(#{traceId}, #{userId}, #{sessionId}, #{intent}, #{mode}, #{inputSummary}, #{toolTraceJson}, #{sourceIds}, #{success}, #{durationMs}, #{modelNames}, #{modelCallsJson}, #{retrievalMs}, #{rerankMs}, #{generationMs}, #{inputTokens}, #{outputTokens}, #{totalTokens}, #{estimatedCost}, #{costCurrency}, #{errorMessage})")
    int insert(AgentTrace trace);

    @Select("select * from tb_agent_trace order by create_time desc limit #{limit}")
    List<AgentTrace> listLatest(@Param("limit") Integer limit);
}
