package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.Classroom;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.service.RoomSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Elasticsearch classroom search")
@RestController
@RequestMapping("/rooms/search")
public class RoomSearchController {
    private final RoomSearchService roomSearchService;

    public RoomSearchController(RoomSearchService roomSearchService) {
        this.roomSearchService = roomSearchService;
    }

    @Operation(summary = "Search classrooms by keyword, capacity, equipment and available time")
    @GetMapping
    public ApiResponse<List<Classroom>> search(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String buildingName,
                                               @RequestParam(required = false) String roomType,
                                               @RequestParam(required = false) Integer minCapacity,
                                               @RequestParam(required = false) String equipment,
                                               @RequestParam(required = false) LocalDate reserveDate,
                                               @RequestParam(required = false) String timeSlot,
                                               @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(roomSearchService.search(keyword, buildingName, roomType, minCapacity,
                equipment, reserveDate, timeSlot, limit));
    }

    @Operation(summary = "Rebuild Elasticsearch classroom index")
    @PostMapping("/sync")
    @RequireRole("ADMIN")
    public ApiResponse<Integer> sync(@RequestParam(defaultValue = "500") Integer limit) {
        return ApiResponse.ok("index rebuild submitted", roomSearchService.rebuildIndex(limit));
    }
}
