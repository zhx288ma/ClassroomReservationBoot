package com.xuan.boot.service;

import com.xuan.boot.domain.Classroom;

import java.util.List;

public interface ClassroomService {
    Classroom detail(Long id);

    List<Classroom> search(String buildingName, String roomType, Integer minCapacity, Boolean includeDisabled, Integer limit);

    Classroom create(Classroom classroom);

    Classroom update(Classroom classroom);
}
