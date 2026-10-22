package com.feng.springailearning.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.LinkedHashMap;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 1. 基础同步调用
     */
    @GetMapping("")
    public String chat(@RequestParam @NotBlank @Size(max = 16000) String msg, @RequestParam(required = false) String sessionId, HttpSession session) {
        return AiResponses.text(chatClient.prompt()
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .user(msg)
                .call()
                .content());
    }

    /**
     * 2. 同步调用 - 返回详细信息
     */
    @GetMapping("/detail")
    public Map<String, Object> chatDetail(@RequestParam @NotBlank @Size(max = 16000) String msg, @RequestParam(required = false) String sessionId, HttpSession session) {
        ChatResponse response = chatClient.prompt()
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .user(msg)
                .call()
                .chatResponse();

        String text = AiResponses.text(response);
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        // 用量可能缺失；保留 null 表示未知，不能用 Map.of，也不能伪造为 0。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", text);
        result.put("model", metadata.getModel());
        result.put("promptTokens", usage == null ? null : usage.getPromptTokens());
        result.put("completionTokens", usage == null ? null : usage.getCompletionTokens());
        result.put("totalTokens", usage == null ? null : usage.getTotalTokens());
        return result;
    }

    /**
     * 3. 流式调用 - SSE 打字机效果
     * curl -N --get \
     *      --data-urlencode "msg=用一句话介绍你自己" \
     *      <a href="http://localhost:8080/chat/stream">...</a>
     */
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chatStream(@RequestParam @NotBlank @Size(max = 16000) String msg, @RequestParam(required = false) String sessionId, HttpSession session) {
        return chatClient.prompt()
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .user(msg)
                .stream()         // 触发流式调用
                .content();       // 返回 Flux<String>，每个元素是一小段文本
    }

    /**
     * 4. 带角色设定的调用
     */
    @GetMapping("/role")
    public String chatWithRole(
            @RequestParam @NotBlank @Size(max = 16000) String msg,
            @RequestParam(defaultValue = "资深Java架构师") @NotBlank @Size(max = 100) String role, @RequestParam(required = false) String sessionId, HttpSession session) {
        return AiResponses.text(chatClient.prompt()
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .system(sys -> {
                    sys.text("你是一位{role}，回答要专业且简洁");
                    sys.param("role", role);
                })
                .user(msg)
                .call()
                .content());
    }

    /**
     * 5. 带参数调优的调用
     */
    @GetMapping({"/creative", "/chat/creative"})
    public String chatCreative(@RequestParam @NotBlank @Size(max = 16000) String msg, @RequestParam(required = false) String sessionId, HttpSession session) {
        return AiResponses.text(chatClient.prompt()
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .user(msg)
                .options(OpenAiChatOptions.builder()
                        .temperature(0.9)
                        .maxTokens(800))
                .call()
                .content());
    }
}
