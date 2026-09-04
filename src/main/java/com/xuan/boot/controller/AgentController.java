package com.xuan.boot.controller;

import com.xuan.boot.dto.AgentChatRequest;
import com.xuan.boot.dto.AgentChatResponse;
import com.xuan.boot.dto.AgentKnowledgeRequest;
import com.xuan.boot.dto.AgentFeedbackAnalysis;
import com.xuan.boot.dto.AgentAnswerReviewRequest;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.domain.AgentKnowledgeDocument;
import com.xuan.boot.domain.AgentTrace;
import com.xuan.boot.config.RequireRole;
import com.xuan.boot.service.AgentKnowledgeService;
import com.xuan.boot.service.AgentTraceService;
import com.xuan.boot.service.AgentService;
import com.xuan.boot.service.AgentEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@Validated
@Tag(name = "智能预约 Agent")
@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentService agentService;
    private final AgentKnowledgeService agentKnowledgeService;
    private final AgentTraceService agentTraceService;
    private final AgentEvaluationService agentEvaluationService;

    public AgentController(AgentService agentService,
                           AgentKnowledgeService agentKnowledgeService,
                           AgentTraceService agentTraceService,
                           AgentEvaluationService agentEvaluationService) {
        this.agentService = agentService;
        this.agentKnowledgeService = agentKnowledgeService;
        this.agentTraceService = agentTraceService;
        this.agentEvaluationService = agentEvaluationService;
    }

    @Operation(summary = "受控智能预约助手：仅查询、推荐和生成待确认预约草稿")
    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        return ApiResponse.ok(agentService.chat(request));
    }

    @Operation(summary = "查看 Agent 知识库文档")
    @RequireRole("ADMIN")
    @GetMapping("/knowledge")
    public ApiResponse<List<AgentKnowledgeDocument>> listKnowledge(@RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(agentKnowledgeService.list(limit));
    }

    @Operation(summary = "新增 Agent 知识库文档并建立检索切片")
    @RequireRole("ADMIN")
    @PostMapping("/knowledge")
    public ApiResponse<AgentKnowledgeDocument> createKnowledge(@Valid @RequestBody AgentKnowledgeRequest request) {
        return ApiResponse.ok("知识文档已建立索引", agentKnowledgeService.create(request));
    }

    @Operation(summary = "上传 PDF、Markdown 或 TXT 并提取文本、切片和建立向量索引")
    @RequireRole("ADMIN")
    @PostMapping(value = "/knowledge/upload", consumes = "multipart/form-data")
    public ApiResponse<AgentKnowledgeDocument> uploadKnowledge(@RequestPart("file") MultipartFile file,
                                                                 @RequestParam(required = false) String title,
                                                                 @RequestParam(defaultValue = "POLICY") String category) {
        return ApiResponse.ok("文件已提取并进入知识索引", agentKnowledgeService.upload(file, title, category));
    }

    @Operation(summary = "查看 Agent 检索与向量库状态")
    @RequireRole("ADMIN")
    @GetMapping("/knowledge/status")
    public ApiResponse<Map<String, Object>> knowledgeStatus() {
        return ApiResponse.ok(agentKnowledgeService.status());
    }

    @Operation(summary = "重新切片并重建全部 Agent 知识文档索引")
    @RequireRole("ADMIN")
    @PostMapping("/knowledge/rebuild")
    public ApiResponse<Integer> rebuildKnowledge() {
        return ApiResponse.ok("知识库重建完成", agentKnowledgeService.rebuildAll());
    }

    @Operation(summary = "删除 Agent 知识文档、切片、向量和本地源文件")
    @RequireRole("ADMIN")
    @DeleteMapping("/knowledge/{documentId}")
    public ApiResponse<Void> removeKnowledge(@org.springframework.web.bind.annotation.PathVariable Long documentId) {
        agentKnowledgeService.remove(documentId);
        return ApiResponse.ok("知识文档已删除", null);
    }

    @Operation(summary = "查看 Agent 工具调用 Trace")
    @RequireRole("ADMIN")
    @GetMapping("/traces")
    public ApiResponse<List<AgentTrace>> traces(@RequestParam(defaultValue = "30") Integer limit) {
        return ApiResponse.ok(agentTraceService.listLatest(limit));
    }

    @Operation(summary = "运行校园规则知识库召回评测，返回 Recall@1、Recall@3 与 MRR@3")
    @RequireRole("ADMIN")
    @GetMapping("/evaluations/retrieval")
    public ApiResponse<Map<String, Object>> evaluateRetrieval() {
        return ApiResponse.ok(agentEvaluationService.evaluateRetrieval());
    }

    @Operation(summary = "运行北京大学外部规章长文档检索评测，区分开发集与保留测试集")
    @RequireRole("ADMIN")
    @GetMapping("/evaluations/external-policy/retrieval")
    public ApiResponse<Map<String, Object>> evaluateExternalPolicyRetrieval() {
        return ApiResponse.ok(agentEvaluationService.evaluateExternalPolicyRetrieval());
    }

    @Operation(summary = "固定规章集逐层诊断与消融：关键词、向量、RRF、Cross-Encoder")
    @RequireRole("ADMIN")
    @GetMapping("/evaluations/external-policy/diagnostics")
    public ApiResponse<Map<String, Object>> diagnoseExternalPolicyRetrieval(
            @RequestParam(defaultValue = "TEST") String split) {
        return ApiResponse.ok(agentEvaluationService.diagnoseExternalPolicyRetrieval(split));
    }

    @Operation(summary = "运行外部规章问答评测，输出引用、自动忠实度代理和人工复核清单")
    @RequireRole("ADMIN")
    @PostMapping("/evaluations/external-policy/answers")
    public ApiResponse<Map<String, Object>> evaluateExternalPolicyAnswers() {
        return ApiResponse.ok(agentEvaluationService.evaluateExternalPolicyAnswers());
    }

    @Operation(summary = "提交一条外部规章答案的人工复核标签")
    @RequireRole("ADMIN")
    @PostMapping("/evaluations/external-policy/reviews")
    public ApiResponse<Map<String, Object>> reviewExternalPolicyAnswer(
            @Valid @RequestBody AgentAnswerReviewRequest request) {
        return ApiResponse.ok("人工复核已保存", agentEvaluationService.reviewExternalPolicyAnswer(request));
    }

    @Operation(summary = "汇总某次答案评测的自动指标与人工指标")
    @RequireRole("ADMIN")
    @GetMapping("/evaluations/external-policy/runs/{runId}")
    public ApiResponse<Map<String, Object>> externalPolicyAnswerSummary(
            @org.springframework.web.bind.annotation.PathVariable String runId) {
        return ApiResponse.ok(agentEvaluationService.externalPolicyAnswerSummary(runId));
    }

    @Operation(summary = "写入可重复的合成校园预约 RAG 评测语料，不覆盖用户上传的知识文档")
    @RequireRole("ADMIN")
    @PostMapping("/evaluations/corpus/seed")
    public ApiResponse<Map<String, Object>> seedEvaluationCorpus() {
        return ApiResponse.ok("评测语料已写入并建立索引", agentEvaluationService.seedEvaluationCorpus());
    }

    @Operation(summary = "运行 Agent 工作流评测：意图、工具、关键事实覆盖、越权拒绝与延迟分位数")
    @RequireRole("ADMIN")
    @PostMapping("/evaluations/agent")
    public ApiResponse<Map<String, Object>> evaluateAgentWorkflows() {
        return ApiResponse.ok(agentEvaluationService.evaluateAgentWorkflows());
    }

    @Operation(summary = "汇总 Agent Trace 成功率、平均耗时和运行模式")
    @RequireRole("ADMIN")
    @GetMapping("/evaluations/agent-metrics")
    public ApiResponse<Map<String, Object>> agentMetrics() {
        return ApiResponse.ok(agentEvaluationService.agentMetrics());
    }

    @Operation(summary = "分析学生反馈分类、优先级与建议回复，不自动发送")
    @RequireRole("ADMIN")
    @PostMapping("/feedbacks/{feedbackId}/analyze")
    public ApiResponse<AgentFeedbackAnalysis> analyzeFeedback(@org.springframework.web.bind.annotation.PathVariable Long feedbackId) {
        return ApiResponse.ok(agentService.analyzeFeedback(feedbackId));
    }
}
