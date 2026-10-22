package com.feng.springailearning.config;

import com.feng.springailearning.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryProperties;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryConfig;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.RedisClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatClientConfigTests {
    @Test
    void customRedisRepositoryAppliesSettingsInsteadOfIgnoringThem() {
        var client = mock(RedisClient.class);
        var properties = new RedisChatMemoryProperties();
        properties.setInitializeSchema(false);
        properties.setIndexName("custom-index");
        properties.setKeyPrefix("custom:");
        properties.setTimeToLive(Duration.ofHours(2));
        properties.setMaxMessagesPerConversation(30);
        var repository = new ChatClientConfig().redisChatMemoryRepository(client, properties);
        var settings = (RedisChatMemoryConfig) ReflectionTestUtils.getField(repository, "config");
        assertThat(settings.getIndexName()).isEqualTo("custom-index");
        assertThat(settings.getKeyPrefix()).isEqualTo("custom:");
        assertThat(settings.getTimeToLiveSeconds()).isEqualTo(7200);
        assertThat(settings.getMaxMessagesPerConversation()).isEqualTo(30);
        verifyNoInteractions(client);
    }

    @Test
    void emptyKnowledgeBaseDoesNotTurnOrdinaryChatIntoRefusal() {
        var config = new ChatClientConfig();
        var rewriteModel = new StubChatModel();
        rewriteModel.responder = prompt -> StubChatModel.reply("你好，介绍一下你自己");
        var model = new StubChatModel();
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var rag = config.retrievalAugmentationAdvisor(store, ChatClient.builder(rewriteModel));
        var client = config.chatClient(ChatClient.builder(model), MessageWindowChatMemory.builder().build(), rag, 5, 10);
        assertThat(client.prompt().user("你好，介绍一下你自己")
                .advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "one")).call().content()).isEqualTo("测试回答");
        assertThat(model.requests.getFirst().getUserMessage().getText())
                .contains("介绍一下你自己").doesNotContain("outside your knowledge base", "can't answer");
        verify(store).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void structuredClientDoesNotRewriteInputOrRegisterWeatherTools() {
        var model = new StubChatModel();
        var client = new ChatClientConfig().structuredChatClient(ChatClient.builder(model), MessageWindowChatMemory.builder().build());
        client.prompt().user("提取 ABC 的价格 10")
                .advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "one"))
                .call().content();
        assertThat(model.requests).hasSize(1);
        assertThat(model.requests.getFirst().getUserMessage().getText()).isEqualTo("提取 ABC 的价格 10");
        assertThat(((org.springframework.ai.openai.OpenAiChatOptions) model.requests.getFirst().getOptions()).getToolCallbacks()).isNullOrEmpty();
    }

    @Test
    void configuredClientExecutesWeatherToolAndFeedsResultBack() {
        var config = new ChatClientConfig();
        var rewriteModel = new StubChatModel();
        rewriteModel.responder = prompt -> StubChatModel.reply("上海天气");
        var model = new StubChatModel();
        var count = new AtomicInteger();
        model.responder = prompt -> count.getAndIncrement() == 0 ? weatherCall() : StubChatModel.reply("工具已执行");
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var client = config.chatClient(ChatClient.builder(model), MessageWindowChatMemory.builder().build(),
                config.retrievalAugmentationAdvisor(store, ChatClient.builder(rewriteModel)), 5, 10);
        assertThat(client.prompt().user("查询上海天气")
                .advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "one"))
                .call().content()).isEqualTo("工具已执行");
        var result = model.requests.getLast().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).findFirst().orElseThrow();
        assertThat(result.getResponses().getFirst().responseData()).contains("模拟数据", "上海");
    }

    public static ChatResponse weatherCall() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getCurrentWeather", "{\"city\":\"上海\"}")))
                .build())));
    }
}
