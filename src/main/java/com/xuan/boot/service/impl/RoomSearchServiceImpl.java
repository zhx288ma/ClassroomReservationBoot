package com.xuan.boot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.domain.Classroom;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.service.RoomSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoomSearchServiceImpl implements RoomSearchService {
    private static final Logger log = LoggerFactory.getLogger(RoomSearchServiceImpl.class);

    private final ClassroomMapper classroomMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();
    private final boolean esEnabled;
    private final String esUrl;
    private final String indexName;

    public RoomSearchServiceImpl(ClassroomMapper classroomMapper,
                                 ObjectMapper objectMapper,
                                 @Value("${reservation.elasticsearch.enabled:false}") boolean esEnabled,
                                 @Value("${reservation.elasticsearch.url:http://localhost:9200}") String esUrl,
                                 @Value("${reservation.elasticsearch.index:classroom_index}") String indexName) {
        this.classroomMapper = classroomMapper;
        this.objectMapper = objectMapper;
        this.esEnabled = esEnabled;
        this.esUrl = trimSlash(esUrl);
        this.indexName = indexName;
    }

    @Override
    public List<Classroom> search(String keyword,
                                  String buildingName,
                                  String roomType,
                                  Integer minCapacity,
                                  String equipment,
                                  LocalDate reserveDate,
                                  String timeSlot,
                                  Integer limit) {
        int safeLimit = Math.min(Math.max(limit == null ? 50 : limit, 1), 100);
        if (!esEnabled) {
            return fallbackSearch(buildingNameOrKeyword(buildingName, keyword), roomType, minCapacity, safeLimit);
        }
        try {
            String body = buildSearchBody(keyword, buildingName, roomType, minCapacity, equipment, reserveDate, timeSlot, safeLimit);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String response = restTemplate.postForObject(esUrl + "/" + indexName + "/_search",
                    new HttpEntity<>(body, headers), String.class);
            return parseSearchResponse(response);
        } catch (Exception exception) {
            log.warn("elasticsearch search failed, fallback to mysql, error={}", exception.getMessage());
            return fallbackSearch(buildingNameOrKeyword(buildingName, keyword), roomType, minCapacity, safeLimit);
        }
    }

    @Override
    public void indexRoom(Classroom classroom) {
        if (!esEnabled || classroom == null || classroom.getId() == null) {
            return;
        }
        try {
            ensureIndex();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.put(esUrl + "/" + indexName + "/_doc/" + classroom.getId(),
                    new HttpEntity<>(toDocument(classroom), headers));
        } catch (Exception exception) {
            log.warn("elasticsearch index room failed, roomId={}, error={}", classroom.getId(), exception.getMessage());
        }
    }

    @Override
    public int rebuildIndex(Integer limit) {
        int safeLimit = Math.min(Math.max(limit == null ? 500 : limit, 1), 2000);
        List<Classroom> rooms = classroomMapper.search(null, null, null, true, safeLimit);
        for (Classroom room : rooms) {
            indexRoom(room);
        }
        return rooms.size();
    }

    private List<Classroom> fallbackSearch(String buildingName, String roomType, Integer minCapacity, int limit) {
        return classroomMapper.search(buildingName, roomType, minCapacity, false, limit);
    }

    private String buildSearchBody(String keyword,
                                   String buildingName,
                                   String roomType,
                                   Integer minCapacity,
                                   String equipment,
                                   LocalDate reserveDate,
                                   String timeSlot,
                                   int limit) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("size", limit);
        List<Map<String, Object>> must = new ArrayList<>();
        List<Map<String, Object>> filter = new ArrayList<>();
        if (notBlank(keyword)) {
            Map<String, Object> multi = new LinkedHashMap<>();
            multi.put("query", keyword);
            multi.put("fields", new String[]{"buildingName^2", "roomNumber^2", "equipment", "roomType"});
            must.add(single("multi_match", multi));
        }
        if (notBlank(buildingName)) {
            must.add(single("match", single("buildingName", buildingName)));
        }
        if (notBlank(roomType)) {
            filter.add(single("term", single("roomType.keyword", roomType)));
        }
        if (minCapacity != null) {
            filter.add(single("range", single("capacity", single("gte", minCapacity))));
        }
        if (notBlank(equipment)) {
            must.add(single("match", single("equipment", equipment)));
        }
        filter.add(single("term", single("status", 1)));
        if (reserveDate != null && notBlank(timeSlot)) {
            must.add(single("match", single("availableSlots", reserveDate + " " + timeSlot)));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", must);
        bool.put("filter", filter);
        root.put("query", single("bool", bool));
        return objectMapper.writeValueAsString(root);
    }

    private List<Classroom> parseSearchResponse(String response) throws Exception {
        List<Classroom> result = new ArrayList<>();
        JsonNode hits = objectMapper.readTree(response).path("hits").path("hits");
        if (!hits.isArray()) {
            return result;
        }
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            Classroom room = new Classroom();
            room.setId(source.path("roomId").asLong());
            room.setBuildingName(text(source, "buildingName"));
            room.setRoomNumber(text(source, "roomNumber"));
            room.setCapacity(source.path("capacity").isMissingNode() ? null : source.path("capacity").asInt());
            room.setRoomType(text(source, "roomType"));
            room.setEquipment(text(source, "equipment"));
            room.setStatus(source.path("status").isMissingNode() ? null : source.path("status").asInt());
            result.add(room);
        }
        return result;
    }

    private String toDocument(Classroom classroom) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("roomId", classroom.getId());
        doc.put("buildingName", classroom.getBuildingName());
        doc.put("roomNumber", classroom.getRoomNumber());
        doc.put("capacity", classroom.getCapacity());
        doc.put("roomType", classroom.getRoomType());
        doc.put("equipment", classroom.getEquipment());
        doc.put("status", classroom.getStatus());
        doc.put("updatedAt", classroom.getUpdateTime());
        return objectMapper.writeValueAsString(doc);
    }

    private void ensureIndex() {
        try {
            restTemplate.put(esUrl + "/" + indexName, null);
        } catch (Exception ignored) {
            // The index may already exist. Mapping is intentionally simple for a first resume-ready version.
        }
    }

    private Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String buildingNameOrKeyword(String buildingName, String keyword) {
        return notBlank(buildingName) ? buildingName : keyword;
    }

    private String text(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull() ? null : node.path(field).asText();
    }

    private String trimSlash(String value) {
        if (value == null || value.endsWith("/")) {
            return value == null ? "" : value.substring(0, value.length() - 1);
        }
        return value;
    }
}
