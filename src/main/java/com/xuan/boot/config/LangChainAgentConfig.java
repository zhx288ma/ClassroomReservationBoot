package com.xuan.boot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import com.xuan.boot.agent.ClassroomToolCallingAssistant;
import com.xuan.boot.agent.ReservationAgentTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Keeps the optional LLM provider outside the reservation transaction path.
 * The bean is absent by default, so a missing API key never blocks startup.
 */
@Configuration
public class LangChainAgentConfig {

    @Bean
    @ConditionalOnProperty(prefix = "reservation.agent.langchain", name = "enabled", havingValue = "true")
    public ChatModel classroomAgentChatModel(
            @Value("${reservation.agent.langchain.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${reservation.agent.langchain.api-key:}") String apiKey,
            @Value("${reservation.agent.langchain.model:gpt-4.1-mini}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("已启用 LangChain4j Agent，但未配置 CLASSROOM_AGENT_LANGCHAIN_API_KEY");
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(20))
                .maxRetries(1)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "reservation.agent.langchain", name = "enabled", havingValue = "true")
    public ClassroomToolCallingAssistant classroomToolCallingAssistant(ChatModel classroomAgentChatModel,
                                                                        ReservationAgentTools reservationAgentTools) {
        return AiServices.builder(ClassroomToolCallingAssistant.class)
                .chatModel(classroomAgentChatModel)
                .tools(reservationAgentTools)
                .build();
    }
}
