package com.feng.springailearning.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Validator;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.openai.OpenAiChatOptions;
import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v3")
public class Stage3Controller {

    private final ChatClient chatClient;

    private final ChatClient conversationalClient;
    private final Validator validator;
    private final String imageModel;

    public Stage3Controller(@Qualifier("structuredChatClient") ChatClient chatClient,
                            @Qualifier("chatClient") ChatClient conversationalClient,
                            Validator validator,
                            @Value("${app.ai.image-model}") String imageModel) {
        this.chatClient = chatClient;
        this.conversationalClient = conversationalClient;
        this.validator = validator;
        this.imageModel = imageModel;
    }

    /**
     * 1. 外部模板文件调用
     */
    @GetMapping("/review")
    public String reviewCode(@RequestParam @NotBlank @Size(max = 64000) String code, @RequestParam(required = false) String sessionId, HttpSession session) {
        // 1. 加载模板文件
        PromptTemplate template = new PromptTemplate(new ClassPathResource("prompts/code-review.st"));
        // 2. 注入变量生成 Prompt 对象
        Prompt prompt = template.create(Map.of(
                "language", "java",
                "code", code)
        );
        // 3. 调用模型
        return AiResponses.text(chatClient.prompt(prompt)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .call().content());
    }

    // 2. 结构化输出 - 单对象
    public record Book(@NotBlank String title, @NotBlank String author,
                       @NotNull @Positive Integer year, @NotBlank String reason) {
    }

    @GetMapping("/book")
    public Book getBook(@RequestParam @NotBlank @Size(max = 1000) String topic, @RequestParam(required = false) String sessionId, HttpSession session) {
        return AiResponses.structured(() -> chatClient.prompt()
                .user(promptUserSpec -> promptUserSpec.text("推荐一本关于{topic}的书")
                        .param("topic", topic))
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .call()
                .entity(Book.class, spec -> spec.validateSchema()), validator);
    }

    // 3. 结构化输出 - 列表泛型
    @GetMapping("/books")
    public List<Book> getBooks(@RequestParam @NotBlank @Size(max = 1000) String topic, @RequestParam @Min(1) @Max(10) int count,
                               @RequestParam(required = false) String sessionId, HttpSession session) {
        List<Book> books = AiResponses.structured(() -> chatClient.prompt()
                .user(promptUserSpec -> promptUserSpec.text("推荐{count}本关于{topic}的书")
                        .params(Map.of("topic", topic, "count", count)))
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .call()
                .entity(new ParameterizedTypeReference<List<Book>>() {
                }, spec -> spec.validateSchema()), validator);
        if (books == null || books.size() != count) {
            throw AiResponses.invalid("模型返回的书籍数量与请求不一致");
        }
        books.forEach(book -> AiResponses.structured(() -> book, validator));
        return books;
    }

    // 4. Schema 校验反馈重试 + 显式业务校验
    public record StockInfo(
            @NotBlank String ticker,
            @NotNull @Min(0) Double price
    ) {
    }

    @GetMapping("/stock")
    public StockInfo getStock(@RequestParam @NotBlank @Size(max = 16000) String text, @RequestParam(required = false) String sessionId, HttpSession session) {
        return AiResponses.structured(() -> chatClient.prompt()
                .user(promptUserSpec -> promptUserSpec.text("提取文本中的股票代码和价格：{text}")
                        .param("text", text))
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .call()
                .entity(StockInfo.class, spec -> spec.validateSchema()), validator);
    }

    // 5. 多模态：图片理解
    @GetMapping("/image")
    public String analyzeImage(@RequestParam(required = false) String sessionId, HttpSession session) {
        return AiResponses.text(chatClient.prompt()
                .options(OpenAiChatOptions.builder().model(imageModel))
                .user(u -> u.text("描述这张图片")
                        .media(MimeTypeUtils.IMAGE_PNG, new ClassPathResource("static/demo.png")))
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .call()
                .content());
    }

    // 6. 添加 redis-memory
    @GetMapping("/chat")
    public String chat(
            @RequestParam @NotBlank @Size(max = 16000) String msg,
            @RequestParam(required = false) String sessionId, HttpSession session) {
        return AiResponses.text(conversationalClient.prompt()
                .user(msg)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, ConversationIds.resolve(sessionId, session)))
                .call()
                .content());
    }
}
