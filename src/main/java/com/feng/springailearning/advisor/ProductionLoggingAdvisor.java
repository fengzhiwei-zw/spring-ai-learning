package com.feng.springailearning.advisor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.util.Assert;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * Metadata logging for synchronous and streaming Spring AI calls.
 *
 * <p>Prompt/completion bodies are disabled by default. Prefer Spring AI's
 * Micrometer observations for metrics and traces; this advisor adds concise,
 * searchable application logs.</p>
 */
public final class ProductionLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ProductionLoggingAdvisor.class);

    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)([\"']?authorization[\"']?\\s*[:=]\\s*[\"']?bearer\\s+)[^\\s,;\"']+"
    );
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)([\"']?(?:api[-_]?key|access[-_]?token|secret|password)[\"']?\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;]+)"
    );

    private final int order;
    private final boolean logContent;
    private final int maxContentCharacters;
    private final boolean includeStackTrace;

    /** Safe production defaults: metadata only, no stack trace or content. */
    public ProductionLoggingAdvisor() {
        this(Ordered.HIGHEST_PRECEDENCE + 50, false, 1_000, false);
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(
            @NonNull ChatClientRequest request, @NonNull StreamAdvisorChain chain) {
        // 每次订阅独立计时和聚合；不提前订阅，不吞异常，也不阻断取消信号。
        return Flux.defer(() -> {
            String requestId = UUID.randomUUID().toString();
            String conversationRef = conversationRef(request);
            long startedNanos = System.nanoTime();
            AtomicReference<ChatClientResponse> aggregated = new AtomicReference<>();
            log.info("ai.stream.started requestId={} conversationRef={}", requestId, conversationRef);
            return new ChatClientMessageAggregator()
                    .aggregateChatClientResponse(Flux.defer(() -> chain.nextStream(request)), aggregated::set)
                    .doOnComplete(() -> {
                        long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
                        if (aggregated.get() != null) {
                            logSuccess(requestId, conversationRef, durationMs, aggregated.get());
                        } else {
                            log.warn("ai.stream.completed requestId={} conversationRef={} durationMs={} responsePresent=false",
                                    requestId, conversationRef, durationMs);
                        }
                    })
                    .doOnError(error -> logFailure(requestId, conversationRef,
                            Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(), error))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            log.info("ai.stream.cancelled requestId={} conversationRef={} durationMs={}",
                                    requestId, conversationRef,
                                    Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
                        }
                    });
        });
    }

    public ProductionLoggingAdvisor(
            int order,
            boolean logContent,
            int maxContentCharacters,
            boolean includeStackTrace) {

        Assert.isTrue(maxContentCharacters >= 0, "maxContentCharacters must be >= 0");
        this.order = order;
        this.logContent = logContent;
        this.maxContentCharacters = maxContentCharacters;
        this.includeStackTrace = includeStackTrace;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(
            @NonNull ChatClientRequest request,
            @NonNull CallAdvisorChain chain) {

        String requestId = UUID.randomUUID().toString();
        String conversationRef = conversationRef(request);
        String userText = Objects.requireNonNullElse(
                request.prompt().getUserMessage().getText(), "");
        int messageCount = request.prompt().getInstructions().size();
        long startedNanos = System.nanoTime();

        log.info(
                "ai.call.started requestId={} conversationRef={} messageCount={} userChars={}",
                requestId, conversationRef, messageCount, userText.length());

        if (this.logContent && log.isDebugEnabled()) {
            log.debug("ai.call.prompt requestId={} content={}",
                    requestId, sanitizeAndTruncate(userText));
        }

        try {
            ChatClientResponse response = chain.nextCall(request);
            long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            logSuccess(requestId, conversationRef, durationMs, response);
            return response;
        }
        catch (RuntimeException ex) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            logFailure(requestId, conversationRef, durationMs, ex);
            throw ex;
        }
    }

    private void logSuccess(
            String requestId,
            String conversationRef,
            long durationMs,
            ChatClientResponse response) {

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            log.warn(
                    "ai.call.completed requestId={} conversationRef={} durationMs={} responsePresent=false",
                    requestId, conversationRef, durationMs);
            return;
        }

        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = metadata.getUsage();

        String model = safeLogValue(metadata.getModel());
        String providerResponseId = safeLogValue(metadata.getId());
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();
        boolean toolCalls = chatResponse.hasToolCalls();

        log.info(
                "ai.call.completed requestId={} conversationRef={} durationMs={} model={} "
                        + "providerResponseId={} promptTokens={} completionTokens={} totalTokens={} toolCalls={}",
                requestId,
                conversationRef,
                durationMs,
                model,
                providerResponseId,
                promptTokens,
                completionTokens,
                totalTokens,
                toolCalls);

        if (this.logContent && log.isDebugEnabled()
                && chatResponse.getResult() != null) {
            String completion = Objects.requireNonNullElse(
                    chatResponse.getResult().getOutput().getText(), "");
            log.debug("ai.call.completion requestId={} content={}",
                    requestId, sanitizeAndTruncate(completion));
        }
    }

    private void logFailure(
            String requestId,
            String conversationRef,
            long durationMs,
            Throwable ex) {

        String errorType = ex.getClass().getName();
        if (this.includeStackTrace) {
            log.error(
                    "ai.call.failed requestId={} conversationRef={} durationMs={} errorType={}",
                    requestId, conversationRef, durationMs, errorType, ex);
        }
        else {
            // Exception messages can contain provider payloads, prompts, or secrets.
            log.error(
                    "ai.call.failed requestId={} conversationRef={} durationMs={} errorType={}",
                    requestId, conversationRef, durationMs, errorType);
        }
    }

    private String conversationRef(ChatClientRequest request) {
        Object conversationId = request.context().get(ChatMemory.CONVERSATION_ID);
        if (conversationId == null) {
            return "none";
        }
        return sha256Prefix(conversationId.toString(), 12);
    }

    private String sanitizeAndTruncate(String content) {
        String sanitized = content
                .replace('\r', ' ')
                .replace('\n', ' ');
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = SECRET_VALUE.matcher(sanitized).replaceAll("$1[REDACTED]");

        if (sanitized.length() <= this.maxContentCharacters) {
            return sanitized;
        }
        return sanitized.substring(0, this.maxContentCharacters) + "...[TRUNCATED]";
    }

    private static String safeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        // Prevent log forging even for provider-controlled metadata.
        return value.replace('\r', '_').replace('\n', '_');
    }

    private static String sha256Prefix(String value, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, length);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    @Override
    public @NonNull String getName() {
        return "ProductionLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }
}
