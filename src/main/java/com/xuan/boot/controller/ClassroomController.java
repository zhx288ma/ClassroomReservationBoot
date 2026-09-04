package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.Classroom;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.service.ClassroomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "教室资源接口")
@RestController
@RequestMapping("/rooms")
public class ClassroomController {
    private final ClassroomService classroomService;

    public ClassroomController(ClassroomService classroomService) {
        this.classroomService = classroomService;
    }

    @Operation(summary = "查询教室列表")
    @GetMapping
    public ApiResponse<List<Classroom>> search(@RequestParam(required = false) String buildingName,
                                               @RequestParam(required = false) String roomType,
                                               @RequestParam(required = false) Integer minCapacity,
                                               @RequestParam(defaultValue = "false") Boolean includeDisabled,
                                               @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(classroomService.search(buildingName, roomType, minCapacity, includeDisabled, limit));
    }

    @Operation(summary = "查询教室详情，带 Redis 缓存")
    @GetMapping("/{id}")
    public ApiResponse<Classroom> detail(@PathVariable Long id) {
        return ApiResponse.ok(classroomService.detail(id));
    }

    @Operation(summary = "新增教室")
    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<Classroom> create(@RequestBody Classroom classroom) {
        return ApiResponse.ok("新增成功", classroomService.create(classroom));
    }

    @Operation(summary = "编辑教室")
    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Classroom> update(@PathVariable Long id, @RequestBody Classroom classroom) {
        classroom.setId(id);
        return ApiResponse.ok("编辑成功", classroomService.update(classroom));
    }
}
