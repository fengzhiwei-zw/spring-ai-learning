package com.feng.springailearning.controller;

import com.feng.springailearning.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Stage3ControllerTests {
    private StubChatModel model;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        model = new StubChatModel();
        ChatClient client = ChatClient.builder(model).build();
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new Stage3Controller(client, client, validator, "test-vision"))
                .setValidator(validator).setControllerAdvice(new AiExceptionHandler()).build();
    }

    @Test
    void schemaValidationFeedsMalformedOutputBackToModel() throws Exception {
        var attempts = new AtomicInteger();
        model.responder = prompt -> StubChatModel.reply(attempts.getAndIncrement() == 0 ? "not json" : book());
        mvc.perform(get("/api/v3/book").param("topic", "Java"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Java 入门"));
        assertThat(model.requests).hasSize(2);
        assertThat(model.requests.getLast().getUserMessage().getText()).contains("Output JSON validation failed");
    }

    @Test
    void missingPriceAndBlankTickerNeverBecomeSuccessfulExtraction() throws Exception {
        model.responder = prompt -> StubChatModel.reply("{\"ticker\":\"ABC\"}");
        mvc.perform(get("/api/v3/stock").param("text", "ABC"))
                .andExpect(status().isBadGateway());
        model.responder = prompt -> StubChatModel.reply("{\"ticker\":\"   \",\"price\":10}");
        mvc.perform(get("/api/v3/stock").param("text", "没有代码，价格为10"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void validatesBookCountAndEveryReturnedItem() throws Exception {
        mvc.perform(get("/api/v3/books").param("topic", "Java").param("count", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v3/books").param("topic", "Java").param("count", "11"))
                .andExpect(status().isBadRequest());
        assertThat(model.requests).isEmpty();
        model.responder = prompt -> StubChatModel.reply("[" + book() + "]");
        mvc.perform(get("/api/v3/books").param("topic", "Java").param("count", "2"))
                .andExpect(status().isBadGateway());
        model.responder = prompt -> StubChatModel.reply("[" + book().replace("Java 入门", "   ") + "]");
        mvc.perform(get("/api/v3/books").param("topic", "Java").param("count", "1"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void imageResourceIsReadableAndUsesConfiguredVisionModel() throws Exception {
        mvc.perform(get("/api/v3/image")).andExpect(status().isOk());
        var prompt = model.requests.getLast();
        assertThat(((OpenAiChatOptions) prompt.getOptions()).getModel()).isEqualTo("test-vision");
        UserMessage user = prompt.getUserMessage();
        assertThat(user.getMedia()).hasSize(1);
        assertThat((byte[]) user.getMedia().getFirst().getData()).startsWith((byte) 0x89, (byte) 0x50);
    }

    @Test
    void reviewTemplateKeepsCodeBracesIntact() throws Exception {
        String code = "class Demo { String json = \"{\\\"a\\\": 1}\"; }";
        mvc.perform(get("/api/v3/review").param("code", code)).andExpect(status().isOk());
        assertThat(model.requests.getLast().getUserMessage().getText()).contains(code);
    }

    private String book() {
        return "{\"title\":\"Java 入门\",\"author\":\"作者\",\"year\":2024,\"reason\":\"适合入门\"}";
    }
}
