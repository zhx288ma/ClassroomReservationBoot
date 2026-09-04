package com.xuan.boot.service;

import com.xuan.boot.dto.AdvisorRecommendation;
import com.xuan.boot.dto.AdvisorRequest;

import java.util.List;

public interface AdvisorService {
    List<AdvisorRecommendation> recommend(AdvisorRequest request);
}
