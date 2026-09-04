package com.xuan.boot.agent;

import com.xuan.boot.domain.Classroom;
import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.domain.User;
import com.xuan.boot.dto.AgentKnowledgeSource;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.service.AgentKnowledgeService;
import com.xuan.boot.service.ReservationService;
import com.xuan.boot.service.RoomSlotService;
import com.xuan.boot.support.UserContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only tools exposed to LangChain4j. Reservation writes are intentionally excluded. */
@Component
public class ReservationAgentTools {
    private final RoomSlotService roomSlotService;
    private final ClassroomMapper classroomMapper;
    private final AgentKnowledgeService knowledgeService;
    private final ReservationService reservationService;

    public ReservationAgentTools(RoomSlotService roomSlotService, ClassroomMapper classroomMapper,
                                 AgentKnowledgeService knowledgeService, ReservationService reservationService) {
        this.roomSlotService = roomSlotService;
        this.classroomMapper = classroomMapper;
        this.knowledgeService = knowledgeService;
        this.reservationService = reservationService;
    }

    @Tool("Searches administrator-opened classroom slots. It never creates a reservation.")
    public List<Map<String, Object>> searchOpenSlots(
            @P("Date in yyyy-MM-dd, optional") String date,
            @P("Time range such as 08:00-10:00, optional") String timeSlot,
            @P("Minimum required seats, optional") Integer minCapacity,
            @P("Building name, optional") String building,
            @P("Equipment keyword, optional") String equipment) {
        LocalDate targetDate = parseDate(date);
        List<Map<String, Object>> results = new ArrayList<>();
        for (RoomSlot slot : roomSlotService.listOpen(100)) {
            if (targetDate != null && !targetDate.equals(slot.getReserveDate())) continue;
            if (notBlank(timeSlot) && !timeSlot.equals(slot.getTimeSlot())) continue;
            if (slot.getAvailableCapacity() == null || slot.getAvailableCapacity() < 1) continue;
            Classroom room = classroomMapper.findById(slot.getRoomId());
            if (room == null || room.getStatus() == null || room.getStatus() != 1) continue;
            if (notBlank(building) && !room.getBuildingName().contains(building)) continue;
            if (minCapacity != null && (room.getCapacity() == null || room.getCapacity() < minCapacity)) continue;
            if (notBlank(equipment) && (room.getEquipment() == null || !room.getEquipment().contains(equipment))) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("roomSlotId", slot.getId()); result.put("roomId", room.getId());
            result.put("building", room.getBuildingName()); result.put("roomNo", room.getRoomNumber());
            result.put("date", slot.getReserveDate()); result.put("timeSlot", slot.getTimeSlot());
            result.put("availableSeats", slot.getAvailableCapacity()); result.put("equipment", room.getEquipment());
            results.add(result);
            if (results.size() == 6) break;
        }
        return results;
    }

    @Tool("Retrieves reservation, check-in, waitlist, credit, and campus policy knowledge with source citations. External reference sources are not enforceable local policy.")
    public List<AgentKnowledgeSource> retrievePolicyKnowledge(@P("User question about reservation or campus policy rules") String query) {
        return knowledgeService.retrieve(query, 3);
    }

    @Tool("Reads only the current authenticated user's latest reservation records. Never exposes other users' data.")
    public List<Map<String, Object>> getMyReservations() {
        User user = UserContext.getRequired();
        List<Map<String, Object>> results = new ArrayList<>();
        reservationService.list(null, null, null, 10).forEach(order -> {
            if (!user.getId().equals(order.getUserId())) return;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", order.getId()); item.put("date", order.getReserveDate());
            item.put("timeSlot", order.getTimeSlot()); item.put("status", order.getStatus());
            results.add(item);
        });
        return results;
    }

    private LocalDate parseDate(String value) { try { return notBlank(value) ? LocalDate.parse(value) : null; } catch (Exception ignored) { return null; } }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
