package com.feng.springailearning.controller;

import com.feng.springailearning.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class Stage4ControllerTests {
    private StubChatModel model;
    private ChatMemory memory;
    private Stage4Controller controller;

    @BeforeEach
    void setUp() {
        model = new StubChatModel();
        memory = MessageWindowChatMemory.builder().build();
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        controller = new Stage4Controller(model, memory, validator);
    }

    @Test
    void putsOneCurrentSystemMessageFirstAndReturnsText() {
        memory.add("one", List.of(new SystemMessage("旧角色"), new UserMessage("之前的问题"), new AssistantMessage("之前的答案")));
        assertThat(controller.model("新的问题", "one", new MockHttpSession())).isEqualTo("测试回答");
        var messages = model.requests.getFirst().getInstructions();
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(messages.stream().filter(SystemMessage.class::isInstance)).hasSize(1);
        assertThat(messages.toString()).doesNotContain("旧角色");
        assertThat(messages.getLast().getText()).isEqualTo("新的问题");
        assertThat(memory.get("one").getLast().getText()).isEqualTo("测试回答");
    }

    @Test
    void modelFailureDoesNotLeaveHalfTurnInMemory() {
        memory.add("one", List.of(new UserMessage("旧问题"), new AssistantMessage("旧答案")));
        var before = List.copyOf(memory.get("one"));
        model.responder = prompt -> { throw new IllegalStateException("模拟模型失败"); };
        assertThatThrownBy(() -> controller.model("新问题", "one", new MockHttpSession()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(memory.get("one")).containsExactlyElementsOf(before);
    }

    @Test
    void parseFailureAndInvalidAgeDoNotPolluteMemory() {
        for (String output : new String[]{"not-json", "{\"name\":\"小明\",\"age\":-1}"}) {
            model.responder = prompt -> StubChatModel.reply(output);
            assertThatThrownBy(() -> controller.format("我的资料", "one", new MockHttpSession()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(memory.get("one")).isEmpty();
        }
    }

    @Test
    void successfulProfileStoresRawUserTurnWithoutSchemaInstructions() {
        model.responder = prompt -> StubChatModel.reply("{\"name\":\"小明\",\"age\":20}");
        assertThat(controller.format("小明今年20岁", "one", new MockHttpSession()).age()).isEqualTo(20);
        assertThat(memory.get("one")).hasSize(2);
        assertThat(memory.get("one").getFirst().getText()).doesNotContain("schema", "$schema");
        assertThat(model.requests.getFirst().getSystemMessage().getText()).contains("schema");
    }
}
