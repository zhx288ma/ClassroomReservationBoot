package com.xuan.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.domain.User;
import com.xuan.boot.dto.AgentChatRequest;
import com.xuan.boot.dto.AgentChatResponse;
import com.xuan.boot.dto.AgentKnowledgeSource;
import com.xuan.boot.dto.AgentRetrievalResult;
import com.xuan.boot.mapper.ClassroomMapper;
import com.xuan.boot.mapper.FeedbackMapper;
import com.xuan.boot.service.AgentKnowledgeService;
import com.xuan.boot.service.AgentSessionService;
import com.xuan.boot.service.AgentTraceService;
import com.xuan.boot.service.EventStatisticsService;
import com.xuan.boot.service.ReservationService;
import com.xuan.boot.service.RoomSlotService;
import com.xuan.boot.service.impl.AgentServiceImpl;
import com.xuan.boot.support.AgentCallMetricsContext;
import com.xuan.boot.support.UserContext;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplTest {

    @AfterEach
    void cleanContext() {
        UserContext.remove();
    }

    @Test
    void ruleQuestionRetrievesEvidenceExactlyOnce() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(null);

        AgentCallMetricsContext metrics = metricsContext();
        AgentServiceImpl service = new AgentServiceImpl(
                mock(RoomSlotService.class), mock(ClassroomMapper.class), mock(ReservationService.class),
                mock(EventStatisticsService.class), knowledgeService, sessionService, traceService,
                mock(FeedbackMapper.class), new ObjectMapper(), chatModelProvider, metrics);
        ReflectionTestUtils.setField(service, "langChainEnabled", false);

        User user = new User();
        user.setId(10L);
        user.setRole("USER");
        UserContext.set(user);

        String question = "签到规则是什么？";
        when(sessionService.enrich(any(), eq(10L), eq(question))).thenReturn(question);
        AgentKnowledgeSource source = new AgentKnowledgeSource();
        source.setDocumentId(1L);
        source.setChunkId(2L);
        source.setTitle("签到规则");
        source.setCategory("POLICY");
        source.setExcerpt("签到窗口为开始前十五分钟至开始后十五分钟");
        AgentRetrievalResult retrieval = new AgentRetrievalResult();
        retrieval.setSources(List.of(source));
        retrieval.setEvidenceTexts(List.of(source.getExcerpt()));
        retrieval.setMetrics(Map.of("totalRetrievalMs", 12L, "rerankMs", 3L));
        when(knowledgeService.retrieveDetailed(question, 3)).thenReturn(retrieval);

        AgentChatRequest request = new AgentChatRequest();
        request.setMessage(question);
        AgentChatResponse response = service.chat(request);

        assertEquals("RULES", response.getIntent());
        assertEquals(12L, response.getStatistics().get("retrievalMs"));
        verify(knowledgeService).retrieveDetailed(question, 3);
        verify(knowledgeService, never()).retrieve(any(), eq(3));
    }

    private AgentCallMetricsContext metricsContext() {
        AgentCallMetricsContext context = new AgentCallMetricsContext();
        ReflectionTestUtils.setField(context, "currency", "CNY");
        ReflectionTestUtils.setField(context, "llmInputPerMillion", BigDecimal.ZERO);
        ReflectionTestUtils.setField(context, "llmOutputPerMillion", BigDecimal.ZERO);
        ReflectionTestUtils.setField(context, "embeddingInputPerMillion", BigDecimal.ZERO);
        ReflectionTestUtils.setField(context, "rerankInputPerMillion", BigDecimal.ZERO);
        return context;
    }
}
