package com.feng.springailearning.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v4")
public class Stage4Controller {

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final Validator validator;

    public Stage4Controller(ChatModel chatModel, ChatMemory chatMemory, Validator validator) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.validator = validator;
    }

    public record UserProfile(
            String name,
            @Min(0) Integer age,
            String city,
            String job,
            List<String> skills
    ) {
    }

    @GetMapping("/model")
    public String model(@RequestParam @NotBlank @Size(max = 16000) String msg,
                        @RequestParam(required = false) String sessionId, HttpSession session) {
        String conversationId = ConversationIds.resolve(sessionId, session);
        UserMessage userMessage = new UserMessage(msg);
        ChatResponse response = chatModel.call(prompt(conversationId, userMessage, null));
        String text = AiResponses.text(response);
        saveTurn(conversationId, userMessage, response);
        return text;
    }

    @GetMapping("/format")
    public UserProfile format(@RequestParam @NotBlank @Size(max = 16000) String msg,
                              @RequestParam(required = false) String sessionId, HttpSession session) {
        String conversationId = ConversationIds.resolve(sessionId, session);
        BeanOutputConverter<UserProfile> converter = new BeanOutputConverter<>(UserProfile.class);
        UserMessage userMessage = new UserMessage("请分析下面的信息：\n" + msg);
        ChatResponse response = chatModel.call(prompt(conversationId, userMessage, converter.getFormat()));
        String text = AiResponses.text(response);
        UserProfile profile = AiResponses.structured(() -> converter.convert(text), validator);
        // 仅在模型调用和解析校验成功后保存完整一轮，失败不会留下半轮记忆。
        saveTurn(conversationId, userMessage, response);
        return profile;
    }

    private Prompt prompt(String conversationId, UserMessage userMessage, String format) {
        List<Message> messages = new ArrayList<>();
        String system = "你是一名Java专家，回答问题要专业、简洁。";
        if (format != null) system += "\n" + format;
        messages.add(new SystemMessage(system));
        chatMemory.get(conversationId).stream()
                .filter(message -> !(message instanceof SystemMessage))
                .forEach(messages::add);
        messages.add(userMessage);
        return new Prompt(messages);
    }

    private void saveTurn(String conversationId, UserMessage userMessage, ChatResponse response) {
        chatMemory.add(conversationId, List.of(userMessage, response.getResult().getOutput()));
    }
}
