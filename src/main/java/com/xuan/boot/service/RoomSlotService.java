package com.xuan.boot.service;

import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.dto.RoomSlotBatchRequest;
import com.xuan.boot.dto.RoomSlotRequest;

import java.time.LocalDate;
import java.util.List;

public interface RoomSlotService {
    RoomSlot create(RoomSlotRequest request);

    int batchCreate(RoomSlotBatchRequest request);

    RoomSlot changeStatus(Long id, Integer status);

    void delete(Long id);

    int reconcileCounters();

    List<RoomSlot> list(Long roomId, LocalDate reserveDate, Integer status, Integer limit);

    List<RoomSlot> listOpen(Integer limit);
}
