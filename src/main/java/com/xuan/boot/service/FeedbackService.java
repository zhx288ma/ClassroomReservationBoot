package com.xuan.boot.service;

import com.xuan.boot.domain.FeedbackTicket;
import com.xuan.boot.dto.FeedbackCreateRequest;
import com.xuan.boot.dto.FeedbackReplyRequest;

import java.util.List;

public interface FeedbackService {
    FeedbackTicket create(FeedbackCreateRequest request);

    List<FeedbackTicket> list(Integer status, Integer limit);

    FeedbackTicket reply(Long id, FeedbackReplyRequest request);

    void close(Long id);
}
