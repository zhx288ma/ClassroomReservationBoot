package com.xuan.boot.service;

import com.xuan.boot.domain.Classroom;

import java.time.LocalDate;
import java.util.List;

public interface RoomSearchService {
    List<Classroom> search(String keyword,
                           String buildingName,
                           String roomType,
                           Integer minCapacity,
                           String equipment,
                           LocalDate reserveDate,
                           String timeSlot,
                           Integer limit);

    void indexRoom(Classroom classroom);

    int rebuildIndex(Integer limit);
}
