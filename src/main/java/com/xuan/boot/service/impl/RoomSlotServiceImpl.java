package com.xuan.boot.service.impl;

import com.xuan.boot.domain.Classroom;
import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.domain.RoomSlotStatus;
import com.xuan.boot.domain.User;
import com.xuan.boot.dto.RoomSlotBatchRequest;
import com.xuan.boot.dto.RoomSlotRequest;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.mapper.ReservationOrderMapper;
import com.xuan.boot.mapper.RoomSlotMapper;
import com.xuan.boot.mapper.WaitlistMapper;
import com.xuan.boot.service.DomainEventService;
import com.xuan.boot.service.RoomSlotService;
import com.xuan.boot.support.RedisKeys;
import com.xuan.boot.support.UserContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoomSlotServiceImpl implements RoomSlotService {
    private final RoomSlotMapper roomSlotMapper;
    private final ClassroomMapper classroomMapper;
    private final ReservationOrderMapper reservationOrderMapper;
    private final WaitlistMapper waitlistMapper;
    private final DomainEventService domainEventService;
    private final StringRedisTemplate stringRedisTemplate;

    public RoomSlotServiceImpl(RoomSlotMapper roomSlotMapper,
                               ClassroomMapper classroomMapper,
                               ReservationOrderMapper reservationOrderMapper,
                               WaitlistMapper waitlistMapper,
                               DomainEventService domainEventService,
                               StringRedisTemplate stringRedisTemplate) {
        this.roomSlotMapper = roomSlotMapper;
        this.classroomMapper = classroomMapper;
        this.reservationOrderMapper = reservationOrderMapper;
        this.waitlistMapper = waitlistMapper;
        this.domainEventService = domainEventService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    @Transactional
    public RoomSlot create(RoomSlotRequest request) {
        User admin = UserContext.getRequired();
        Classroom classroom = requireUsableRoom(request.getRoomId());
        int status = normalizeStatus(request.getStatus());
        int capacity = request.getCapacity() == null ? classroom.getCapacity() : request.getCapacity();
        int inserted = roomSlotMapper.insertManaged(
                request.getRoomId(),
                request.getReserveDate(),
                request.getTimeSlot(),
                Math.max(capacity, 1),
                status,
                normalizeOpenType(request.getOpenType()),
                admin.getId());
        if (inserted == 0) {
            throw new IllegalArgumentException("同一教室同一日期同一时间段已经存在 room_slot");
        }
        RoomSlot slot = roomSlotMapper.find(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        recordSlotEvent(slot, status == RoomSlotStatus.OPEN ? "ROOM_SLOT_OPENED" : "ROOM_SLOT_CREATED");
        return slot;
    }

    @Override
    @Transactional
    public int batchCreate(RoomSlotBatchRequest request) {
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        int created = 0;
        LocalDate date = request.getStartDate();
        while (!date.isAfter(request.getEndDate())) {
            for (Long roomId : request.getRoomIds()) {
                Classroom classroom = requireUsableRoom(roomId);
                for (String timeSlot : request.getTimeSlots()) {
                    RoomSlotRequest item = new RoomSlotRequest();
                    item.setRoomId(roomId);
                    item.setReserveDate(date);
                    item.setTimeSlot(timeSlot);
                    item.setCapacity(request.getCapacity() == null ? classroom.getCapacity() : request.getCapacity());
                    item.setStatus(request.getStatus());
                    item.setOpenType(request.getOpenType());
                    try {
                        create(item);
                        created++;
                    } catch (IllegalArgumentException ignored) {
                        // Duplicate slots are skipped in batch creation so admins can safely rerun a batch.
                    }
                }
            }
            date = date.plusDays(1);
        }
        return created;
    }

    @Override
    @Transactional
    public RoomSlot changeStatus(Long id, Integer status) {
        RoomSlot slot = roomSlotMapper.findById(id);
        if (slot == null) {
            throw new IllegalArgumentException("room_slot 不存在");
        }
        int targetStatus = normalizeStatus(status);
        if (targetStatus == RoomSlotStatus.CLOSED || targetStatus == RoomSlotStatus.MAINTENANCE || targetStatus == RoomSlotStatus.TEACHER_BOOKED) {
            assertNoActiveBusiness(slot, "该时段已有学生预约或候补，第一版不允许直接关闭、维护或教师占用");
        }
        roomSlotMapper.updateStatus(id, targetStatus);
        RoomSlot updated = roomSlotMapper.findById(id);
        recordSlotEvent(updated, slotEventType(targetStatus));
        return updated;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        RoomSlot slot = roomSlotMapper.findById(id);
        if (slot == null) {
            throw new IllegalArgumentException("room_slot 不存在");
        }
        assertNoActiveBusiness(slot, "该时段仍有学生预约或候补，不能删除");
        recordSlotEvent(slot, "ROOM_SLOT_DELETED");
        roomSlotMapper.deleteById(id);
        deleteRedisSlotKeys(slot);
    }

    @Override
    @Transactional
    public int reconcileCounters() {
        int updated = roomSlotMapper.reconcileCounters();
        domainEventService.recordEvent("ROOM_SLOT_COUNTER_RECONCILED", "ROOM_SLOT", 0L, UserContext.getRequired().getId(),
                null, null, null, null, null, null, null);
        return updated;
    }

    private void assertNoActiveBusiness(RoomSlot slot, String message) {
        int activeCount = reservationOrderMapper.countActiveByRoomTime(slot.getRoomId(), slot.getReserveDate(), slot.getTimeSlot());
        int waitingCount = waitlistMapper.countWaitingByRoomTime(slot.getRoomId(), slot.getReserveDate(), slot.getTimeSlot());
        if (activeCount > 0 || waitingCount > 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private void deleteRedisSlotKeys(RoomSlot slot) {
        String suffix = slot.getRoomId() + ":" + slot.getReserveDate() + ":" + slot.getTimeSlot();
        stringRedisTemplate.delete(Arrays.asList(
                RedisKeys.RESERVE_STOCK + suffix,
                RedisKeys.RESERVE_USERS + suffix
        ));
    }

    @Override
    public List<RoomSlot> list(Long roomId, LocalDate reserveDate, Integer status, Integer limit) {
        return roomSlotMapper.list(roomId, reserveDate, status, limit == null ? 100 : limit);
    }

    @Override
    public List<RoomSlot> listOpen(Integer limit) {
        return roomSlotMapper.listOpen(LocalDate.now(), limit == null ? 100 : limit);
    }

    private Classroom requireUsableRoom(Long roomId) {
        Classroom classroom = classroomMapper.findById(roomId);
        if (classroom == null) {
            throw new IllegalArgumentException("教室不存在");
        }
        if (classroom.getStatus() == null || classroom.getStatus() != 1) {
            throw new IllegalArgumentException("教室已停用，不能创建开放时段");
        }
        return classroom;
    }

    private int normalizeStatus(Integer status) {
        if (status == null) {
            return RoomSlotStatus.OPEN;
        }
        if (status < RoomSlotStatus.CLOSED || status > RoomSlotStatus.EXPIRED) {
            throw new IllegalArgumentException("room_slot 状态不合法");
        }
        return status;
    }

    private String normalizeOpenType(String openType) {
        if (openType == null || openType.trim().isEmpty()) {
            return "SELF_STUDY";
        }
        return openType.trim().toUpperCase();
    }

    private void recordSlotEvent(RoomSlot slot, String eventType) {
        if (slot == null) {
            return;
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("status", slot.getStatus());
        attributes.put("openType", slot.getOpenType());
        attributes.put("capacity", slot.getTotalCapacity());
        domainEventService.recordEvent(eventType, "ROOM_SLOT", slot.getId(), slot.getCreatedBy(),
                slot.getRoomId(), slot.getId(), null, null,
                slot.getReserveDate(), slot.getTimeSlot(), attributes);
    }

    private String slotEventType(int status) {
        if (status == RoomSlotStatus.OPEN) {
            return "ROOM_SLOT_OPENED";
        }
        if (status == RoomSlotStatus.CLOSED) {
            return "ROOM_SLOT_CLOSED";
        }
        if (status == RoomSlotStatus.MAINTENANCE) {
            return "ROOM_SLOT_MAINTENANCE";
        }
        if (status == RoomSlotStatus.TEACHER_BOOKED) {
            return "TEACHER_BOOKING_APPROVED";
        }
        return "ROOM_SLOT_STATUS_CHANGED";
    }
}
