package com.xuan.boot.controller;

import com.xuan.boot.dto.AdvisorRecommendation;
import com.xuan.boot.dto.AdvisorRequest;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.service.AdvisorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "智能预约助手")
@RestController
@RequestMapping("/advisor")
public class AdvisorController {
    private final AdvisorService advisorService;

    public AdvisorController(AdvisorService advisorService) {
        this.advisorService = advisorService;
    }

    @Operation(summary = "可解释教室推荐")
    @PostMapping("/recommend")
    public ApiResponse<List<AdvisorRecommendation>> recommend(@Validated @RequestBody AdvisorRequest request) {
        return ApiResponse.ok("推荐成功", advisorService.recommend(request));
    }
}
