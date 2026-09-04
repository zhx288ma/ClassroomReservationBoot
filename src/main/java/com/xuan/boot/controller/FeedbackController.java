package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.FeedbackTicket;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.dto.FeedbackCreateRequest;
import com.xuan.boot.dto.FeedbackReplyRequest;
import com.xuan.boot.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "学生反馈工单")
@RestController
@RequestMapping("/feedbacks")
public class FeedbackController {
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Operation(summary = "学生提交问题反馈")
    @RequireRole("USER")
    @PostMapping
    public ApiResponse<FeedbackTicket> create(@Validated @RequestBody FeedbackCreateRequest request) {
        return ApiResponse.ok("反馈提交成功", feedbackService.create(request));
    }

    @Operation(summary = "查询反馈工单")
    @GetMapping
    public ApiResponse<List<FeedbackTicket>> list(@RequestParam(required = false) Integer status,
                                                  @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(feedbackService.list(status, limit));
    }

    @Operation(summary = "管理员回复反馈")
    @RequireRole("ADMIN")
    @PostMapping("/{id}/reply")
    public ApiResponse<FeedbackTicket> reply(@PathVariable Long id,
                                             @Validated @RequestBody FeedbackReplyRequest request) {
        return ApiResponse.ok("回复成功", feedbackService.reply(id, request));
    }

    @Operation(summary = "关闭反馈工单")
    @PostMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id) {
        feedbackService.close(id);
        return ApiResponse.ok("反馈已关闭", null);
    }
}
