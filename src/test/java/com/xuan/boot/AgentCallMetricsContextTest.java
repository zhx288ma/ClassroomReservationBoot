package com.xuan.boot;

import com.xuan.boot.support.AgentCallMetricsContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCallMetricsContextTest {

    @Test
    void aggregatesTokensModelsAndConfiguredCost() {
        AgentCallMetricsContext context = new AgentCallMetricsContext();
        ReflectionTestUtils.setField(context, "currency", "CNY");
        ReflectionTestUtils.setField(context, "llmInputPerMillion", new BigDecimal("2"));
        ReflectionTestUtils.setField(context, "llmOutputPerMillion", new BigDecimal("4"));
        ReflectionTestUtils.setField(context, "embeddingInputPerMillion", BigDecimal.ZERO);
        ReflectionTestUtils.setField(context, "rerankInputPerMillion", BigDecimal.ZERO);

        context.begin("trace-1");
        context.record("CHAT_GENERATION", "DEEPSEEK", "deepseek-chat", 120,
                1000, 500, 1500, false);
        AgentCallMetricsContext.Snapshot snapshot = context.snapshot();

        assertEquals(1000, snapshot.inputTokens());
        assertEquals(500, snapshot.outputTokens());
        assertEquals(1500, snapshot.totalTokens());
        assertEquals(new BigDecimal("0.004000"), snapshot.estimatedCost());
        assertEquals("deepseek-chat", snapshot.models().get(0));
        assertTrue(snapshot.costConfigured());
        context.clear();
    }
}
