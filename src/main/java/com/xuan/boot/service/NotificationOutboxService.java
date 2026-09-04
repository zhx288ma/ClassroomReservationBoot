package com.xuan.boot.service;

import com.xuan.boot.domain.NotificationOutbox;

import java.util.List;
import java.util.Map;

public interface NotificationOutboxService {
    void enqueue(Long userId, String title, String content);

    int publishDueMessages();

    List<NotificationOutbox> listLatest(Integer limit);

    Map<String, Object> overview();
}
