package com.feng.springailearning.controller;

import com.feng.springailearning.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTests {
    private StubChatModel model;
    private ChatMemory memory;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        model = new StubChatModel();
        memory = MessageWindowChatMemory.builder().build();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(client))
                .setValidator(validator).setControllerAdvice(new AiExceptionHandler()).build();
    }

    @Test
    void implicitSessionsKeepMemoryWithoutSharingItBetweenVisitors() throws Exception {
        var first = new MockHttpSession();
        mvc.perform(get("/chat").param("msg", "我叫小明").session(first)).andExpect(status().isOk());
        mvc.perform(get("/chat").param("msg", "我叫什么").session(first)).andExpect(status().isOk());
        assertThat(model.requests.get(1).getInstructions().toString()).contains("我叫小明");
        mvc.perform(get("/chat").param("msg", "我叫什么").session(new MockHttpSession()))
                .andExpect(status().isOk());
        assertThat(model.requests.get(2).getInstructions().toString()).doesNotContain("我叫小明");
    }

    @Test
    void explicitConversationIdStillWorksForExistingClients() throws Exception {
        mvc.perform(get("/chat").param("msg", "你好").param("sessionId", "demo-001"))
                .andExpect(status().isOk());
        assertThat(memory.get("demo-001")).hasSize(2);
    }

    @Test
    void rejectsBadInputBeforeInvokingModel() throws Exception {
        mvc.perform(get("/chat").param("msg", "   ")).andExpect(status().isBadRequest());
        mvc.perform(get("/chat").param("msg", "a".repeat(16001))).andExpect(status().isBadRequest());
        mvc.perform(get("/chat").param("msg", "你好").param("sessionId", "   "))
                .andExpect(status().isBadRequest());
        assertThat(model.requests).isEmpty();
    }

    @Test
    void detailAcceptsMissingUsageAndRejectsEmptyResponses() throws Exception {
        Usage unavailable = new Usage() {
            public Integer getPromptTokens() { return null; }
            public Integer getCompletionTokens() { return null; }
            public Integer getTotalTokens() { return null; }
            public Object getNativeUsage() { return null; }
        };
        model.responder = prompt -> new ChatResponse(StubChatModel.reply("正常回答").getResults(),
                ChatResponseMetadata.builder().model(null).usage(unavailable).build());
        mvc.perform(get("/chat/detail").param("msg", "你好"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("正常回答"))
                .andExpect(jsonPath("$.model").value(nullValue()))
                .andExpect(jsonPath("$.totalTokens").value(nullValue()));
        model.responder = prompt -> new ChatResponse(java.util.List.of());
        mvc.perform(get("/chat/detail").param("msg", "你好"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void correctedCreativePathKeepsOldAlias() throws Exception {
        for (String path : new String[]{"/chat/creative", "/chat/chat/creative"}) {
            mvc.perform(get(path).param("msg", "写一首诗")).andExpect(status().isOk());
            assertThat(model.requests.getLast().getOptions().getTemperature()).isEqualTo(0.9);
            assertThat(model.requests.getLast().getOptions().getMaxTokens()).isEqualTo(800);
        }
    }
}
