package com.xuan.boot.mapper;

import com.xuan.boot.dto.AgentAnswerReviewRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface AgentAnswerEvaluationMapper {
    @Insert("insert into tb_agent_answer_evaluation(run_id, case_id, split_name, expected_answerable, question, expected_anchor, answer, source_ids, source_evidence, auto_citation_correct, auto_faithful, auto_refusal_correct, auto_ungrounded, evidence_support_rate, retrieval_ms, rerank_ms, generation_ms, model_names, input_tokens, output_tokens, total_tokens, estimated_cost, cost_currency) values(#{runId}, #{caseId}, #{splitName}, #{expectedAnswerable}, #{question}, #{expectedAnchor}, #{answer}, #{sourceIds}, #{sourceEvidence}, #{autoCitationCorrect}, #{autoFaithful}, #{autoRefusalCorrect}, #{autoUngrounded}, #{evidenceSupportRate}, #{retrievalMs}, #{rerankMs}, #{generationMs}, #{modelNames}, #{inputTokens}, #{outputTokens}, #{totalTokens}, #{estimatedCost}, #{costCurrency})")
    int insert(Map<String, Object> row);

    @Update("update tb_agent_answer_evaluation set human_citation_correct=#{review.citationCorrect}, human_faithful=#{review.faithful}, human_correct=#{review.correct}, human_refusal_correct=#{review.refusalCorrect}, human_ungrounded=#{review.ungrounded}, review_comment=#{review.comment}, reviewer_id=#{reviewerId}, reviewed_at=now() where run_id=#{review.runId} and case_id=#{review.caseId}")
    int review(@Param("review") AgentAnswerReviewRequest review, @Param("reviewerId") Long reviewerId);

    @Select("select id, run_id as runId, case_id as caseId, split_name as splitName, expected_answerable as expectedAnswerable, question, expected_anchor as expectedAnchor, answer, source_ids as sourceIds, source_evidence as sourceEvidence, auto_citation_correct as autoCitationCorrect, auto_faithful as autoFaithful, auto_refusal_correct as autoRefusalCorrect, auto_ungrounded as autoUngrounded, evidence_support_rate as evidenceSupportRate, retrieval_ms as retrievalMs, rerank_ms as rerankMs, generation_ms as generationMs, model_names as modelNames, input_tokens as inputTokens, output_tokens as outputTokens, total_tokens as totalTokens, estimated_cost as estimatedCost, cost_currency as costCurrency, human_citation_correct as humanCitationCorrect, human_faithful as humanFaithful, human_correct as humanCorrect, human_refusal_correct as humanRefusalCorrect, human_ungrounded as humanUngrounded, review_comment as reviewComment, reviewer_id as reviewerId, reviewed_at as reviewedAt from tb_agent_answer_evaluation where run_id=#{runId} order by case_id")
    List<Map<String, Object>> listByRunId(@Param("runId") String runId);
}
