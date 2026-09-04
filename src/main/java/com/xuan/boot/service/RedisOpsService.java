package com.xuan.boot.service;

import com.xuan.boot.dto.RedisStockSyncRequest;

import java.util.Map;

public interface RedisOpsService {
    Map<String, Object> overview();

    Map<String, Object> stock(Long roomId, String reserveDate, String timeSlot);

    Map<String, Object> syncStock(RedisStockSyncRequest request);

    Map<String, Object> hotRooms(int limit);

    void clearDemoKeys();
}
