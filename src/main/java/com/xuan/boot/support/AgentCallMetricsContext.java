package com.xuan.boot.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request-scoped model usage ledger backed by ThreadLocal. Agent calls are
 * synchronous today, so every embedding, rerank and chat invocation can append
 * usage without coupling the individual provider adapters to AgentServiceImpl.
 */
@Component
public class AgentCallMetricsContext {
    private final ThreadLocal<State> current = new ThreadLocal<>();

    @Value("${reservation.agent.cost.currency:CNY}")
    private String currency;
    @Value("${reservation.agent.cost.llm-input-per-million:0}")
    private BigDecimal llmInputPerMillion;
    @Value("${reservation.agent.cost.llm-output-per-million:0}")
    private BigDecimal llmOutputPerMillion;
    @Value("${reservation.agent.cost.embedding-input-per-million:0}")
    private BigDecimal embeddingInputPerMillion;
    @Value("${reservation.agent.cost.rerank-input-per-million:0}")
    private BigDecimal rerankInputPerMillion;

    public void begin(String traceId) {
        current.set(new State(traceId));
    }

    public void record(String stage, String provider, String model, long durationMs,
                       Integer inputTokens, Integer outputTokens, Integer totalTokens,
                       boolean tokenEstimated) {
        State state = current.get();
        if (state == null) return;
        int safeInput = nonNegative(inputTokens);
        int safeOutput = nonNegative(outputTokens);
        int safeTotal = totalTokens == null ? safeInput + safeOutput : nonNegative(totalTokens);
        BigDecimal cost = estimateCost(stage, safeInput, safeOutput, safeTotal);
        state.calls.add(new ModelCall(stage, provider, model, durationMs, safeInput, safeOutput,
                safeTotal, tokenEstimated, cost));
    }

    public Snapshot snapshot() {
        State state = current.get();
        if (state == null) return Snapshot.empty(currency);
        Set<String> models = new LinkedHashSet<>();
        List<Map<String, Object>> calls = new ArrayList<>();
        int input = 0, output = 0, total = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean costConfigured = false;
        for (ModelCall call : state.calls) {
            if (call.model != null && !call.model.isBlank()) models.add(call.model);
            input += call.inputTokens;
            output += call.outputTokens;
            total += call.totalTokens;
            if (call.cost != null) {
                cost = cost.add(call.cost);
                costConfigured = true;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", call.stage);
            row.put("provider", call.provider);
            row.put("model", call.model);
            row.put("durationMs", call.durationMs);
            row.put("inputTokens", call.inputTokens);
            row.put("outputTokens", call.outputTokens);
            row.put("totalTokens", call.totalTokens);
            row.put("tokenEstimated", call.tokenEstimated);
            row.put("estimatedCost", call.cost);
            row.put("currency", currency);
            calls.add(row);
        }
        return new Snapshot(new ArrayList<>(models), calls, input, output, total,
                costConfigured ? cost.setScale(6, RoundingMode.HALF_UP) : null,
                currency, costConfigured);
    }

    public void clear() {
        current.remove();
    }

    /** Provider responses do not always include usage, so this is an explicit fallback estimate. */
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int ascii = 0;
        for (int index = 0; index < text.length(); index++) if (text.charAt(index) <= 127) ascii++;
        int nonAscii = text.length() - ascii;
        return Math.max(1, (int) Math.ceil(ascii / 4D + nonAscii / 1.7D));
    }

    private BigDecimal estimateCost(String stage, int input, int output, int total) {
        BigDecimal million = BigDecimal.valueOf(1_000_000L);
        if ("CHAT_INTENT".equals(stage) || "CHAT_GENERATION".equals(stage)) {
            if (positive(llmInputPerMillion) || positive(llmOutputPerMillion)) {
                return BigDecimal.valueOf(input).multiply(llmInputPerMillion).divide(million, 12, RoundingMode.HALF_UP)
                        .add(BigDecimal.valueOf(output).multiply(llmOutputPerMillion).divide(million, 12, RoundingMode.HALF_UP));
            }
        } else if ("EMBEDDING".equals(stage) && positive(embeddingInputPerMillion)) {
            return BigDecimal.valueOf(total).multiply(embeddingInputPerMillion).divide(million, 12, RoundingMode.HALF_UP);
        } else if ("RERANK".equals(stage) && positive(rerankInputPerMillion)) {
            return BigDecimal.valueOf(total).multiply(rerankInputPerMillion).divide(million, 12, RoundingMode.HALF_UP);
        }
        return null;
    }

    private boolean positive(BigDecimal value) { return value != null && value.compareTo(BigDecimal.ZERO) > 0; }
    private int nonNegative(Integer value) { return value == null ? 0 : Math.max(value, 0); }

    private static final class State {
        private final String traceId;
        private final List<ModelCall> calls = new ArrayList<>();
        private State(String traceId) { this.traceId = traceId; }
    }

    private record ModelCall(String stage, String provider, String model, long durationMs,
                             int inputTokens, int outputTokens, int totalTokens,
                             boolean tokenEstimated, BigDecimal cost) { }

    public record Snapshot(List<String> models, List<Map<String, Object>> calls,
                           int inputTokens, int outputTokens, int totalTokens,
                           BigDecimal estimatedCost, String currency, boolean costConfigured) {
        private static Snapshot empty(String currency) {
            return new Snapshot(List.of(), List.of(), 0, 0, 0, null, currency, false);
        }
    }
}
