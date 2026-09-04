package com.xuan.boot.service.impl;

import com.xuan.boot.domain.Classroom;
import com.xuan.boot.domain.AgentTrace;
import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.domain.User;
import com.xuan.boot.domain.FeedbackTicket;
import com.xuan.boot.dto.AgentCandidate;
import com.xuan.boot.dto.AgentChatRequest;
import com.xuan.boot.dto.AgentChatResponse;
import com.xuan.boot.dto.AgentToolTrace;
import com.xuan.boot.dto.AgentKnowledgeSource;
import com.xuan.boot.dto.AgentRetrievalResult;
import com.xuan.boot.dto.ReservationDraft;
import com.xuan.boot.dto.AgentFeedbackAnalysis;
import com.xuan.boot.service.AgentService;
import com.xuan.boot.service.AgentKnowledgeService;
import com.xuan.boot.service.AgentSessionService;
import com.xuan.boot.service.AgentTraceService;
import com.xuan.boot.service.EventStatisticsService;
import com.xuan.boot.service.ReservationService;
import com.xuan.boot.service.RoomSlotService;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.mapper.FeedbackMapper;
import com.xuan.boot.support.UserContext;
import com.xuan.boot.support.AgentCallMetricsContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.math.BigDecimal;

/**
 * The agent is intentionally a bounded tool orchestrator. It produces a draft
 * but never calls the reservation write path on behalf of a user.
 */
@Service
public class AgentServiceImpl implements AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{1,2}-\\d{1,2})");
    private static final Pattern CHINESE_DATE = Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern CAPACITY = Pattern.compile("(\\d{1,4})\\s*(?:人|座)");
    private static final Pattern BUILDING = Pattern.compile("([\\p{IsHan}A-Za-z0-9]+楼)");
    private static final Pattern HOUR_RANGE = Pattern.compile("(?:上午|下午|晚上|晚)?\\s*(\\d{1,2})(?:点|:)(?:(\\d{2})分?)?\\s*(?:到|至|-|~)\\s*(\\d{1,2})(?:点|:)(?:(\\d{2})分?)?");
    private static final String[] EQUIPMENT = {"投影", "白板", "空调", "插座", "录播"};

    private final RoomSlotService roomSlotService;
    private final ClassroomMapper classroomMapper;
    private final ReservationService reservationService;
    private final EventStatisticsService eventStatisticsService;
    private final AgentKnowledgeService agentKnowledgeService;
    private final AgentSessionService agentSessionService;
    private final AgentTraceService agentTraceService;
    private final FeedbackMapper feedbackMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> agentChatModel;
    private final AgentCallMetricsContext callMetrics;

    @Value("${reservation.agent.langchain.enabled:false}")
    private boolean langChainEnabled;
    @Value("${reservation.agent.langchain.base-url:https://api.openai.com/v1}")
    private String langChainBaseUrl;
    @Value("${reservation.agent.langchain.model:gpt-4.1-mini}")
    private String configuredChatModel;

    public AgentServiceImpl(RoomSlotService roomSlotService,
                            ClassroomMapper classroomMapper,
                            ReservationService reservationService,
                            EventStatisticsService eventStatisticsService,
                            AgentKnowledgeService agentKnowledgeService,
                            AgentSessionService agentSessionService,
                            AgentTraceService agentTraceService,
                            FeedbackMapper feedbackMapper,
                            ObjectMapper objectMapper,
                            ObjectProvider<ChatModel> agentChatModel,
                            AgentCallMetricsContext callMetrics) {
        this.roomSlotService = roomSlotService;
        this.classroomMapper = classroomMapper;
        this.reservationService = reservationService;
        this.eventStatisticsService = eventStatisticsService;
        this.agentKnowledgeService = agentKnowledgeService;
        this.agentSessionService = agentSessionService;
        this.agentTraceService = agentTraceService;
        this.feedbackMapper = feedbackMapper;
        this.objectMapper = objectMapper;
        this.agentChatModel = agentChatModel;
        this.callMetrics = callMetrics;
    }

    @Override
    public AgentFeedbackAnalysis analyzeFeedback(Long feedbackId) {
        User admin = UserContext.getRequired();
        if (!"ADMIN".equals(admin.getRole())) {
            throw new IllegalArgumentException("只有管理员可以使用工单 Copilot");
        }
        FeedbackTicket ticket = feedbackMapper.findById(feedbackId);
        if (ticket == null) {
            throw new IllegalArgumentException("反馈工单不存在");
        }
        String text = (ticket.getTitle() + " " + ticket.getContent()).toLowerCase(Locale.ROOT);
        AgentFeedbackAnalysis result = new AgentFeedbackAnalysis();
        result.setFeedbackId(ticket.getId());
        result.setSummary(abbreviate(ticket.getContent(), 160));
        if (containsAny(text, "投影", "空调", "插座", "白板", "设备", "损坏")) {
            result.setCategory("FACILITY");
            result.setPriority("P1");
            result.setSuggestedReply("已收到设备异常反馈。管理员将核查教室设备状态并协调维护，处理进展会通过站内通知同步。");
            result.getEvidence().add("命中设备或故障关键词");
        } else if (containsAny(text, "签到", "签到码", "打卡")) {
            result.setCategory("CHECKIN");
            result.setPriority("P1");
            result.setSuggestedReply("已收到签到异常反馈。管理员会核对预约状态、签到窗口和签到记录后处理，请保留预约时间与签到码信息。");
            result.getEvidence().add("命中签到相关关键词");
        } else if (containsAny(text, "候补", "预约", "名额", "取消")) {
            result.setCategory("RESERVATION");
            result.setPriority("P2");
            result.setSuggestedReply("已收到预约或候补问题。管理员会核对 room_slot 名额、订单状态和候补顺序后回复。");
            result.getEvidence().add("命中预约或候补关键词");
        } else if (containsAny(text, "账号", "登录", "密码", "手机号")) {
            result.setCategory("ACCOUNT");
            result.setPriority("P2");
            result.setSuggestedReply("已收到账号问题。管理员会核对账号状态并通过安全方式协助处理。");
            result.getEvidence().add("命中账号相关关键词");
        } else {
            result.setCategory("GENERAL");
            result.setPriority("P3");
            result.setSuggestedReply("已收到你的反馈，管理员会核对相关信息后尽快回复。");
            result.getEvidence().add("未命中专用分类，进入人工复核队列");
        }
        return result;
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) { if (text.contains(term)) { return true; } }
        return false;
    }

    private String abbreviate(String value, int max) {
        if (value == null) { return ""; }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    @Override
    public AgentChatResponse chat(AgentChatRequest request) {
        User user = UserContext.getRequired();
        String traceId = "agent-" + UUID.randomUUID().toString().replace("-", "");
        callMetrics.begin(traceId);
        String originalMessage;
        long started;
        String message;
        long routingStarted;
        boolean directWriteRequest;
        AgentIntent intent;
        try {
            originalMessage = request.getMessage().trim();
            started = System.nanoTime();
            message = agentSessionService.enrich(request.getSessionId(), user.getId(), originalMessage);
            agentSessionService.append(request.getSessionId(), user.getId(), "user", originalMessage);
            routingStarted = System.nanoTime();
            directWriteRequest = isDirectWriteRequest(originalMessage);
            intent = directWriteRequest ? AgentIntent.SEARCH_AVAILABLE_SLOTS : parseIntent(message, user);
        } catch (RuntimeException exception) {
            callMetrics.clear();
            throw exception;
        }
        AgentChatResponse response = new AgentChatResponse();
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("intentRoutingMs", elapsedMs(routingStarted));
        timing.put("firstTokenMs", null);
        timing.put("firstTokenMetric", "同步模型调用不支持首 Token 观测；需切换流式模型客户端后启用");
        response.setStatistics(timing);
        response.setTraceId(traceId);
        response.setIntent(intent.name());
        response.setMode("HYBRID_RAG_TOOL_AGENT");

        try {
            if (directWriteRequest) {
                response.setIntent("WRITE_ACTION_REFUSED");
                response.setMode("SAFETY_GUARD");
                response.setReply("为保护预约与名额一致性，助手不能直接提交预约、取消预约、签到或修改库存。请在对应页面核对信息后由你确认操作。");
                response.getToolTraces().add(trace("reject_unsafe_write_action", "拦截模型代替用户执行的写操作请求", started));
                response.getNextActions().add("前往“我的预约”或预约页面确认操作");
            } else if (intent == AgentIntent.RULES) {
                long retrievalStarted = System.nanoTime();
                AgentRetrievalResult retrievalResult = agentKnowledgeService.retrieveDetailed(message, 3);
                List<AgentKnowledgeSource> sources = retrievalResult.getSources();
                timing.put("retrievalMs", number(retrievalResult.getMetrics().get("totalRetrievalMs"), elapsedMs(retrievalStarted)));
                timing.put("rerankMs", number(retrievalResult.getMetrics().get("rerankMs"), 0));
                timing.put("retrievalStages", retrievalResult.getMetrics());
                response.setSources(sources);
                response.getToolTraces().add(trace("retrieve_policy_knowledge", "检索预约规章知识库，命中 " + sources.size() + " 个片段", retrievalStarted));
                response.setReply(sources.isEmpty()
                        ? "知识库暂未命中相关规定。你可以换用更具体的制度名称、事项或关键词重新提问。"
                        : knowledgeReply(sources));
                long generationStarted = System.nanoTime();
                String groundedAnswer = generateGroundedAnswerIfEnabled(originalMessage, retrievalResult);
                long generationMs = elapsedMs(generationStarted);
                timing.put("generationMs", generationMs);
                // Retained for old evaluation reports; this is now grounded generation, not a second tool retrieval.
                timing.put("modelToolCallingMs", generationMs);
                if (groundedAnswer != null) {
                    response.setReply(groundedAnswer);
                    response.setMode("LANGCHAIN4J_GROUNDED_RAG");
                    response.getToolTraces().add(trace("langchain4j_grounded_generation", "模型基于已检索证据生成回答，未重复检索", generationStarted));
                }
                response.getNextActions().add("如需预约，描述日期、时间、人数、楼栋或设备条件");
            } else if (intent == AgentIntent.MY_RESERVATIONS) {
                response.getToolTraces().add(trace("get_my_reservations", "查询当前用户预约记录", started));
                int count = reservationService.list(null, null, null, 10).size();
                response.setReply("已查询到你最近 " + count + " 条预约记录。可在“我的预约”页面完成签到、取消预约或退出候补。");
                response.getNextActions().add("前往“我的预约”查看详情");
            } else if (intent == AgentIntent.USAGE_STATISTICS) {
                if (!"ADMIN".equals(user.getRole())) {
                    throw new IllegalArgumentException("学生账号不能查询全校运营统计，已为你切换为可预约时段推荐");
                }
                response.getToolTraces().add(trace("get_usage_statistics", "读取管理员统计看板", started));
                timing.putAll(eventStatisticsService.dashboard(null));
                response.setReply("已生成运营统计摘要。你可以结合候补数、签到率和爽约率决定是否新增或调整开放时段。");
                response.getNextActions().add("根据候补量批量创建开放时段");
            } else {
                List<AgentCandidate> candidates = searchSlots(message, started, response);
                if (candidates.isEmpty()) {
                    candidates = searchAlternativeSlots(message, started, response);
                    response.setCandidates(candidates);
                    if (candidates.isEmpty()) {
                        response.setReply("目标日期和时间段暂未被管理员开放。管理员创建并开放 room_slot 后，学生才能预约；你也可以改选其他日期或时间段。");
                        response.getNextActions().add("放宽日期或时间条件后重新检索");
                    } else {
                        response.setReply("目标时段暂未开放，已为你列出最近的可预约替代时段。选择后会回填预约表单，仍需你确认提交。");
                        response.getNextActions().add("选择替代时段并在预约页确认提交");
                    }
                } else {
                    response.setCandidates(candidates);
                    AgentCandidate first = candidates.get(0);
                    response.setReply("已找到 " + candidates.size() + " 个可预约时段，优先推荐 "
                            + first.getBuildingName() + " " + first.getRoomNumber() + "，剩余 "
                            + first.getAvailableCapacity() + " 个名额。请选择候选项后确认预约。");
                    if (intent == AgentIntent.RESERVATION_DRAFT) {
                        response.setDraft(toDraft(first));
                        response.setRequiresConfirmation(true);
                    }
                    response.getNextActions().add("选择候选项，回填预约表单后生成一次性令牌");
                }
            }
            timing.put("totalResponseMs", elapsedMs(started));
            appendModelUsage(timing);
            agentSessionService.append(request.getSessionId(), user.getId(), "assistant", response.getReply());
            recordTrace(traceId, user, request, intent, response, originalMessage, started, null);
            log.info("agent trace={}, userId={}, intent={}, tools={}", traceId, user.getId(), response.getIntent(), response.getToolTraces().size());
            return response;
        } catch (RuntimeException exception) {
            timing.put("totalResponseMs", elapsedMs(started));
            appendModelUsage(timing);
            recordTrace(traceId, user, request, intent, response, originalMessage, started, exception.getMessage());
            log.warn("agent trace={} failed, intent={}, message={}", traceId, intent, exception.getMessage());
            throw exception;
        } finally {
            callMetrics.clear();
        }
    }

    private String joinKnowledge(List<AgentKnowledgeSource> sources) {
        return sources.stream().map(source -> "《" + source.getTitle() + "》：" + source.getExcerpt())
                .collect(Collectors.joining("；"));
    }

    private String knowledgeReply(List<AgentKnowledgeSource> sources) {
        boolean hasExternalReference = sources.stream()
                .anyMatch(source -> "EXTERNAL_REFERENCE".equalsIgnoreCase(source.getCategory()));
        String prefix = hasExternalReference
                ? "以下内容包含外部高校公开资料，仅作参考，不构成本系统的生效校规；请以来源学校和本校正式通知为准。"
                : "我已依据本系统知识库检索到相关规定：";
        return prefix + joinKnowledge(sources);
    }

    private boolean isDirectWriteRequest(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", "");
        boolean writeVerb = containsAny(normalized, "直接预约", "直接提交", "替我预约", "替我取消", "取消所有预约", "取消全部预约", "取消全部", "直接取消", "替我签到", "直接签到", "修改库存");
        return writeVerb && containsAny(normalized, "预约", "取消", "签到", "库存");
    }

    /**
     * Generates from the evidence retrieved earlier in the same request. The model
     * receives no retrieval tool here, so embedding and rerank APIs are called once.
     */
    private String generateGroundedAnswerIfEnabled(String message, AgentRetrievalResult retrievalResult) {
        if (!langChainEnabled) return null;
        ChatModel chatModel = agentChatModel.getIfAvailable();
        if (chatModel == null || retrievalResult.getEvidenceTexts().isEmpty()) return null;
        try {
            StringBuilder evidence = new StringBuilder();
            for (int index = 0; index < retrievalResult.getSources().size(); index++) {
                AgentKnowledgeSource source = retrievalResult.getSources().get(index);
                String content = index < retrievalResult.getEvidenceTexts().size()
                        ? retrievalResult.getEvidenceTexts().get(index) : source.getExcerpt();
                evidence.append("[S").append(index + 1).append("] 来源《")
                        .append(source.getTitle()).append("》，分类=").append(source.getCategory())
                        .append("\n").append(content).append("\n\n");
            }
            String system = "你是校园规则问答助手。只能依据用户消息中提供的 PRE_RETRIEVED_EVIDENCE 回答，"
                    + "禁止补充证据中没有的事实。每个关键结论后使用 [S1] 形式标注来源。"
                    + "EXTERNAL_REFERENCE 是外部高校公开资料，必须说明来源学校且仅供参考，不能表述为本系统生效规则。"
                    + "如果证据不能直接回答，明确回复‘知识库暂无足够依据’，不要猜测。";
            String prompt = "问题：" + message + "\n\nPRE_RETRIEVED_EVIDENCE:\n" + evidence;
            ChatResponse result = invokeChat(chatModel, "CHAT_GENERATION", system, prompt);
            String answer = result == null || result.aiMessage() == null ? null : result.aiMessage().text();
            return answer == null || answer.isBlank() ? null : answer;
        } catch (Exception exception) {
            log.warn("LangChain4j grounded generation failed; use local RAG fallback: {}", exception.getMessage());
            return null;
        }
    }

    private void recordTrace(String traceId, User user, AgentChatRequest request, AgentIntent intent,
                             AgentChatResponse response, String input, long started, String errorMessage) {
        AgentTrace trace = new AgentTrace();
        trace.setTraceId(traceId);
        trace.setUserId(user.getId());
        trace.setSessionId(request.getSessionId());
        trace.setIntent(response.getIntent() == null ? intent.name() : response.getIntent());
        trace.setMode(response.getMode());
        trace.setInputSummary(input.length() > 500 ? input.substring(0, 500) : input);
        trace.setSuccess(errorMessage == null ? 1 : 0);
        trace.setDurationMs(Math.max(1, (System.nanoTime() - started) / 1_000_000));
        trace.setErrorMessage(errorMessage == null ? null : errorMessage.substring(0, Math.min(errorMessage.length(), 500)));
        try {
            trace.setToolTraceJson(objectMapper.writeValueAsString(response.getToolTraces()));
            trace.setSourceIds(response.getSources().stream().map(item -> String.valueOf(item.getDocumentId())).distinct().collect(Collectors.joining(",")));
            Map<String, Object> statistics = response.getStatistics() == null ? Map.of() : response.getStatistics();
            Object models = statistics.get("models");
            if (models instanceof List<?> values) trace.setModelNames(values.stream().map(String::valueOf).collect(Collectors.joining(",")));
            trace.setModelCallsJson(objectMapper.writeValueAsString(statistics.getOrDefault("modelCalls", List.of())));
            trace.setRetrievalMs(numberOrNull(statistics.get("retrievalMs")));
            trace.setRerankMs(numberOrNull(statistics.get("rerankMs")));
            trace.setGenerationMs(numberOrNull(statistics.get("generationMs")));
            trace.setInputTokens(integerOrNull(statistics.get("inputTokens")));
            trace.setOutputTokens(integerOrNull(statistics.get("outputTokens")));
            trace.setTotalTokens(integerOrNull(statistics.get("totalTokens")));
            Object estimatedCost = statistics.get("estimatedCost");
            if (estimatedCost != null) trace.setEstimatedCost(new BigDecimal(String.valueOf(estimatedCost)));
            trace.setCostCurrency(statistics.get("costCurrency") == null ? null : String.valueOf(statistics.get("costCurrency")));
        } catch (Exception ignored) {
            trace.setToolTraceJson("[]");
        }
        agentTraceService.record(trace);
    }

    private List<AgentCandidate> searchSlots(String message, long started, AgentChatResponse response) {
        SearchFilters filters = SearchFilters.from(message);
        List<RoomSlot> slots = roomSlotService.listOpen(100);
        List<AgentCandidate> candidates = new ArrayList<>();
        for (RoomSlot slot : slots) {
            if (filters.date != null && !filters.date.equals(slot.getReserveDate())) {
                continue;
            }
            if (filters.timeSlot != null && !filters.timeSlot.equals(slot.getTimeSlot())) {
                continue;
            }
            if (slot.getAvailableCapacity() == null || slot.getAvailableCapacity() <= 0) {
                continue;
            }
            // Agent retrieval must not affect the hot-room ranking metric.
            Classroom room = classroomMapper.findById(slot.getRoomId());
            if (room == null || room.getStatus() == null || room.getStatus() != 1) {
                continue;
            }
            if (filters.building != null && !room.getBuildingName().contains(filters.building)) {
                continue;
            }
            if (filters.minCapacity != null && (room.getCapacity() == null || room.getCapacity() < filters.minCapacity)) {
                continue;
            }
            if (filters.equipment != null && (room.getEquipment() == null || !room.getEquipment().contains(filters.equipment))) {
                continue;
            }
            candidates.add(toCandidate(room, slot, filters));
        }
        candidates.sort(Comparator.comparing(AgentCandidate::getAvailableCapacity).reversed()
                .thenComparing(AgentCandidate::getCapacity));
        response.getToolTraces().add(trace("search_available_slots", describeFilters(filters) + "，返回 " + candidates.size() + " 条候选", started));
        return candidates.stream().limit(6).collect(Collectors.toList());
    }

    private List<AgentCandidate> searchAlternativeSlots(String message, long started, AgentChatResponse response) {
        SearchFilters filters = SearchFilters.from(message);
        List<AgentCandidate> alternatives = new ArrayList<>();
        for (RoomSlot slot : roomSlotService.listOpen(100)) {
            if (slot.getAvailableCapacity() == null || slot.getAvailableCapacity() <= 0) {
                continue;
            }
            Classroom room = classroomMapper.findById(slot.getRoomId());
            if (room == null || room.getStatus() == null || room.getStatus() != 1) {
                continue;
            }
            if (filters.building != null && !room.getBuildingName().contains(filters.building)) {
                continue;
            }
            if (filters.minCapacity != null && (room.getCapacity() == null || room.getCapacity() < filters.minCapacity)) {
                continue;
            }
            if (filters.equipment != null && (room.getEquipment() == null || !room.getEquipment().contains(filters.equipment))) {
                continue;
            }
            AgentCandidate candidate = toCandidate(room, slot, filters);
            candidate.setReason("目标时段未开放；这是最近可预约的替代时段，剩余 " + slot.getAvailableCapacity() + " 个名额");
            alternatives.add(candidate);
        }
        alternatives.sort(Comparator.comparing(AgentCandidate::getReserveDate)
                .thenComparing(AgentCandidate::getTimeSlot)
                .thenComparing(AgentCandidate::getAvailableCapacity, Comparator.reverseOrder()));
        response.getToolTraces().add(trace("suggest_nearest_open_slots", "目标时段无结果，返回 " + alternatives.size() + " 条替代时段", started));
        return alternatives.stream().limit(3).collect(Collectors.toList());
    }

    private AgentCandidate toCandidate(Classroom room, RoomSlot slot, SearchFilters filters) {
        AgentCandidate candidate = new AgentCandidate();
        candidate.setRoomId(room.getId());
        candidate.setRoomSlotId(slot.getId());
        candidate.setBuildingName(room.getBuildingName());
        candidate.setRoomNumber(room.getRoomNumber());
        candidate.setCapacity(room.getCapacity());
        candidate.setAvailableCapacity(slot.getAvailableCapacity());
        candidate.setReserveDate(slot.getReserveDate());
        candidate.setTimeSlot(slot.getTimeSlot());
        candidate.setEquipment(room.getEquipment());
        candidate.setReason("开放时段，剩余 " + slot.getAvailableCapacity() + " 个名额"
                + (filters.minCapacity == null ? "" : "；容量满足 " + filters.minCapacity + " 人")
                + (filters.equipment == null ? "" : "；匹配设备“" + filters.equipment + "”"));
        return candidate;
    }

    private ReservationDraft toDraft(AgentCandidate candidate) {
        ReservationDraft draft = new ReservationDraft();
        draft.setRoomId(candidate.getRoomId());
        draft.setRoomSlotId(candidate.getRoomSlotId());
        draft.setReserveDate(candidate.getReserveDate());
        draft.setTimeSlot(candidate.getTimeSlot());
        return draft;
    }

    private AgentToolTrace trace(String name, String summary, long started) {
        return new AgentToolTrace(name, summary, elapsedMs(started), true);
    }

    private long elapsedMs(long started) {
        return Math.max(1, (System.nanoTime() - started) / 1_000_000);
    }

    private String describeFilters(SearchFilters filters) {
        return "日期=" + (filters.date == null ? "开放时段" : filters.date)
                + "，时间=" + (filters.timeSlot == null ? "不限" : filters.timeSlot)
                + "，人数=" + (filters.minCapacity == null ? "不限" : filters.minCapacity)
                + "，楼栋=" + (filters.building == null ? "不限" : filters.building);
    }

    private AgentIntent parseIntent(String message, User user) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("规则") || normalized.contains("签到") || normalized.contains("信用") || normalized.contains("候补怎么")
                || containsAny(normalized, "校规", "校纪", "学生管理", "管理规定", "处分", "奖学金", "助学金", "学籍", "宿舍", "请假", "违纪")
                || containsAny(normalized, "kafka", "rabbitmq", "elasticsearch", "redis", "room_slot", "outbox", "超卖", "库存")
                || ((normalized.contains("agent") || normalized.contains("助手")) && containsAny(normalized, "能否", "可以", "直接", "工具", "权限"))) {
            return AgentIntent.RULES;
        }
        if (normalized.contains("我的预约") || normalized.contains("我的候补")) {
            return AgentIntent.MY_RESERVATIONS;
        }
        if ("ADMIN".equals(user.getRole()) && (normalized.contains("统计") || normalized.contains("使用率") || normalized.contains("候补率"))) {
            return AgentIntent.USAGE_STATISTICS;
        }
        if (normalized.contains("帮我预约") || normalized.contains("帮我预定") || normalized.contains("生成草稿")) {
            return AgentIntent.RESERVATION_DRAFT;
        }
        // Only ambiguous requests pay for model routing; obvious business intents stay local.
        AgentIntent modelIntent = resolveIntentWithModel(message, user);
        if (modelIntent != null) return modelIntent;
        return AgentIntent.SEARCH_AVAILABLE_SLOTS;
    }

    /**
     * Optional LangChain4j classifier. The model can only choose from a fixed
     * set of read-only intents; tool execution and authorization remain local.
     */
    private AgentIntent resolveIntentWithModel(String message, User user) {
        ChatModel chatModel = agentChatModel.getIfAvailable();
        if (!langChainEnabled || chatModel == null) {
            return null;
        }
        try {
            String prompt = "Return only JSON: {\"intent\": one of SEARCH_AVAILABLE_SLOTS, RESERVATION_DRAFT, RULES, MY_RESERVATIONS, USAGE_STATISTICS}. "
                    + "Never request or perform a write action. USAGE_STATISTICS is allowed only for ADMIN. "
                    + "Reservation rules, campus regulations, student-management rules, discipline, scholarships, academic status, accommodation, leave, and policy questions are RULES. "
                    + "role=" + user.getRole() + "; request=" + message;
            ChatResponse response = invokeChat(chatModel, "CHAT_INTENT",
                    "你只负责把请求分类为固定意图，输出JSON，不执行任何业务操作。", prompt);
            String raw = response.aiMessage().text();
            String value = objectMapper.readTree(raw).path("intent").asText("");
            AgentIntent resolved = AgentIntent.valueOf(value);
            if (resolved == AgentIntent.USAGE_STATISTICS && !"ADMIN".equals(user.getRole())) {
                return null;
            }
            return resolved;
        } catch (Exception exception) {
            log.warn("LangChain4j agent intent extraction failed; use deterministic fallback: {}", exception.getMessage());
            return null;
        }
    }

    private ChatResponse invokeChat(ChatModel chatModel, String stage, String system, String prompt) {
        long startedAt = System.nanoTime();
        ChatResponse response = chatModel.chat(SystemMessage.from(system), UserMessage.from(prompt));
        TokenUsage usage = response.tokenUsage();
        Integer input = usage == null ? null : usage.inputTokenCount();
        Integer output = usage == null ? null : usage.outputTokenCount();
        Integer total = usage == null ? null : usage.totalTokenCount();
        boolean estimated = usage == null || total == null;
        int safeInput = input == null ? callMetrics.estimateTokens(system + "\n" + prompt) : input;
        String answer = response.aiMessage() == null ? "" : response.aiMessage().text();
        int safeOutput = output == null ? callMetrics.estimateTokens(answer) : output;
        callMetrics.record(stage, chatProvider(), response.modelName() == null ? configuredChatModel : response.modelName(),
                elapsedMs(startedAt), safeInput, safeOutput,
                total == null ? safeInput + safeOutput : total, estimated);
        return response;
    }

    private void appendModelUsage(Map<String, Object> timing) {
        AgentCallMetricsContext.Snapshot usage = callMetrics.snapshot();
        timing.put("models", usage.models());
        timing.put("modelCalls", usage.calls());
        timing.put("inputTokens", usage.inputTokens());
        timing.put("outputTokens", usage.outputTokens());
        timing.put("totalTokens", usage.totalTokens());
        timing.put("estimatedCost", usage.estimatedCost());
        timing.put("costCurrency", usage.currency());
        timing.put("costConfigured", usage.costConfigured());
    }

    private String chatProvider() {
        String value = langChainBaseUrl == null ? "" : langChainBaseUrl.toLowerCase(Locale.ROOT);
        if (value.contains("deepseek")) return "DEEPSEEK";
        if (value.contains("aliyun") || value.contains("dashscope")) return "ALIBABA_BAILIAN";
        if (value.contains("openai")) return "OPENAI";
        return "OPENAI_COMPATIBLE";
    }

    private long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private Long numberOrNull(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private Integer integerOrNull(Object value) { return value instanceof Number number ? number.intValue() : null; }

    private enum AgentIntent {
        SEARCH_AVAILABLE_SLOTS, RESERVATION_DRAFT, RULES, MY_RESERVATIONS, USAGE_STATISTICS
    }

    private static class SearchFilters {
        private LocalDate date;
        private String timeSlot;
        private Integer minCapacity;
        private String building;
        private String equipment;

        private static SearchFilters from(String message) {
            SearchFilters filters = new SearchFilters();
            filters.date = parseDate(message);
            filters.timeSlot = parseTimeSlot(message);
            Matcher capacity = CAPACITY.matcher(message);
            if (capacity.find()) {
                filters.minCapacity = Integer.valueOf(capacity.group(1));
            }
            Matcher building = BUILDING.matcher(message);
            if (building.find()) {
                filters.building = building.group(1);
            }
            for (String item : EQUIPMENT) {
                if (message.contains(item)) {
                    filters.equipment = item;
                    break;
                }
            }
            return filters;
        }

        private static LocalDate parseDate(String message) {
            if (message.contains("后天")) {
                return LocalDate.now().plusDays(2);
            }
            if (message.contains("明天")) {
                return LocalDate.now().plusDays(1);
            }
            Matcher iso = ISO_DATE.matcher(message);
            if (iso.find()) {
                return LocalDate.parse(iso.group(1), DateTimeFormatter.ofPattern("yyyy-M-d"));
            }
            Matcher chinese = CHINESE_DATE.matcher(message);
            if (chinese.find()) {
                return LocalDate.of(Integer.parseInt(chinese.group(1)), Integer.parseInt(chinese.group(2)), Integer.parseInt(chinese.group(3)));
            }
            return null;
        }

        private static String parseTimeSlot(String message) {
            Matcher matcher = HOUR_RANGE.matcher(message);
            if (!matcher.find()) {
                return null;
            }
            int startHour = Integer.parseInt(matcher.group(1));
            int startMinute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            int endHour = Integer.parseInt(matcher.group(3));
            int endMinute = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            boolean night = message.contains("晚上") || message.contains("晚");
            if (night && startHour < 12) { startHour += 12; }
            if (night && endHour < 12) { endHour += 12; }
            return String.format("%02d:%02d-%02d:%02d", startHour, startMinute, endHour, endMinute);
        }
    }
}
