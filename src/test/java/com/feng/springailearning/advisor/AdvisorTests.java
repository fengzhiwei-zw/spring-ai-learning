package com.feng.springailearning.advisor;

import com.feng.springailearning.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdvisorTests {

    @Test
    void contentLoggingRedactsQuotedJsonSecretsAndBearerTokens() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProductionLoggingAdvisor.class);
        Level previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            var advisor = new ProductionLoggingAdvisor(100, true, 2000, false);
            var request = ChatClientRequest.builder().prompt(new Prompt(
                    "{\"api_key\":\"key-123\",\"password\":\"long secret with spaces\"} Authorization: Bearer token-456\nnext"))
                    .context(ChatMemory.CONVERSATION_ID, "private-conversation-id").build();
            var chain = mock(CallAdvisorChain.class);
            when(chain.nextCall(request)).thenReturn(ChatClientResponse.builder().chatResponse(StubChatModel.reply("完成")).build());
            advisor.adviseCall(request, chain);
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("[REDACTED]").doesNotContain("key-123", "long secret with spaces", "token-456", "private-conversation-id");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void toolBudgetIsNotSharedAcrossRequests() {
        var model = new StubChatModel();
        var tool = new CounterTool();
        var attempts = new AtomicInteger();
        model.responder = prompt -> attempts.getAndIncrement() % 2 == 0 ? counterCall() : StubChatModel.reply("完成");
        var client = ChatClient.builder(model).defaultTools(tool)
                .defaultAdvisors(new LimitedToolCallingAdvisor(1, 1)).build();
        assertThat(client.prompt().user("第一次").call().content()).isEqualTo("完成");
        assertThat(client.prompt().user("第二次").call().content()).isEqualTo("完成");
        assertThat(tool.calls.get()).isEqualTo(2);
    }

    @Test
    void repeatedToolRequestsStopBeforeExecutingOverBudgetBatchForCallAndStream() {
        for (boolean stream : new boolean[]{false, true}) {
            var model = new StubChatModel();
            var tool = new CounterTool();
            model.responder = prompt -> counterCall();
            var client = ChatClient.builder(model).defaultTools(tool)
                    .defaultAdvisors(new LimitedToolCallingAdvisor(2, 10)).build();
            assertThatThrownBy(() -> {
                if (stream) client.prompt().user("一直调用").stream().content().collectList().block(Duration.ofSeconds(5));
                else client.prompt().user("一直调用").call().content();
            }).isInstanceOf(LimitedToolCallingAdvisor.LimitExceededException.class);
            assertThat(tool.calls.get()).isEqualTo(2);
            assertThat(model.requests).hasSize(3);
        }
    }

    @Test
    void perBatchToolCountIsCheckedBeforeAnyToolExecutes() {
        var model = new StubChatModel();
        var tool = new CounterTool();
        var call = new AssistantMessage.ToolCall("one", "function", "increment", "{}");
        var second = new AssistantMessage.ToolCall("two", "function", "increment", "{}");
        model.responder = prompt -> new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().toolCalls(List.of(call, second)).build())));
        var client = ChatClient.builder(model).defaultTools(tool)
                .defaultAdvisors(new LimitedToolCallingAdvisor(5, 1)).build();
        assertThatThrownBy(() -> client.prompt().user("多次调用").call().content())
                .isInstanceOf(LimitedToolCallingAdvisor.LimitExceededException.class);
        assertThat(tool.calls.get()).isZero();
    }

    @Test
    void streamLoggingPreservesChunksErrorsAndCancellation() {
        var advisor = new ProductionLoggingAdvisor();
        var chain = mock(StreamAdvisorChain.class);
        var request = ChatClientRequest.builder().prompt(new Prompt("你好")).build();
        when(chain.nextStream(request)).thenReturn(Flux.just(
                ChatClientResponse.builder().chatResponse(StubChatModel.reply("你")).build(),
                ChatClientResponse.builder().chatResponse(StubChatModel.reply("好")).build()));
        var values = advisor.adviseStream(request, chain)
                .map(response -> response.chatResponse().getResult().getOutput().getText())
                .collectList().block(Duration.ofSeconds(5));
        assertThat(values).containsExactly("你", "好");
        var failure = new IllegalStateException("模拟失败");
        when(chain.nextStream(request)).thenReturn(Flux.error(failure));
        assertThatThrownBy(() -> advisor.adviseStream(request, chain).collectList().block(Duration.ofSeconds(5)))
                .isSameAs(failure);
        var cancelled = new AtomicBoolean();
        when(chain.nextStream(request)).thenReturn(Flux.<ChatClientResponse>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = advisor.adviseStream(request, chain).subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
    }

    @Test
    void longTermMemoryKeepsValidMarkupAndPrefersRecentEntriesWithinBudget() throws Exception {
        var store = mock(VectorStore.class);
        var old = new Document("旧偏好", Map.of("updatedAt", "2024-01-01T00:00:00Z"));
        var recent = new Document("新偏好 <中文> & emoji 😀", Map.of("updatedAt", "2026-01-01T00:00:00Z"));
        var oversized = new Document("&".repeat(1000), Map.of("updatedAt", "2026-02-01T00:00:00Z"));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(old, oversized, recent));
        var advisor = new ProductionVectorMemoryAdvisor(store, 4, 0.72, 256, 100, true);
        var request = ChatClientRequest.builder().prompt(new Prompt("我的偏好是什么"))
                .context(Map.of(ProductionVectorMemoryAdvisor.TENANT_ID, "tenant", ProductionVectorMemoryAdvisor.MEMORY_OWNER_ID, "user"))
                .build();
        String augmented = advisor.before(request, null).prompt().getSystemMessage().getText();
        String block = augmented.substring(augmented.indexOf("<long-term-memory>"), augmented.indexOf("</long-term-memory>") + "</long-term-memory>".length());
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(block.getBytes(StandardCharsets.UTF_8)));
        assertThat(block).contains("&lt;中文&gt;", "&amp;", "😀");
        assertThat(block.indexOf("新偏好")).isLessThan(block.indexOf("旧偏好"));
        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression().toString()).contains("tenantId", "ownerId", "active");
    }

    @Test
    void longTermWriterRejectsAmbiguousScopeIdsBeforeWriting() {
        var store = mock(VectorStore.class);
        var writer = new LongTermMemoryWriter(store);
        var fact = new LongTermMemoryWriter.MemoryFact("key", "preference", "中文", "one");
        assertThatThrownBy(() -> writer.remember("a\u001fb", "c", fact)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    private ChatResponse counterCall() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("call", "function", "increment", "{}"))).build())));
    }

    public static class CounterTool {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "计数")
        public int increment() { return calls.incrementAndGet(); }
    }
}
