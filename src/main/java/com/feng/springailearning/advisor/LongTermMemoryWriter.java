package com.feng.springailearning.advisor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.util.Assert;

/**
 * Explicit writer for already extracted long-term facts.
 *
 * <p>Call this from an after-commit event handler or message consumer. Do not
 * call it for every raw user/assistant message.</p>
 */
public final class LongTermMemoryWriter {

    private final VectorStore vectorStore;
    private final Clock clock;

    public LongTermMemoryWriter(VectorStore vectorStore) {
        this(vectorStore, Clock.systemUTC());
    }

    LongTermMemoryWriter(VectorStore vectorStore, Clock clock) {
        Assert.notNull(vectorStore, "vectorStore must not be null");
        Assert.notNull(clock, "clock must not be null");
        this.vectorStore = vectorStore;
        this.clock = clock;
    }

    /**
     * Upserts one logical fact. memoryKey must be stable, for example
     * "project.java-version" or "preferences.response-language".
     */
    public String remember(String tenantId, String ownerId, MemoryFact fact) {
        validateScope(tenantId, ownerId);
        Objects.requireNonNull(fact, "fact must not be null");
        fact.validate();

        String documentId = deterministicId(tenantId, ownerId, fact.memoryKey());
        Instant now = this.clock.instant();

        Document document = Document.builder()
                .id(documentId)
                .text(fact.content())
                .metadata(Map.of(
                        ProductionVectorMemoryAdvisor.META_TENANT_ID, tenantId,
                        ProductionVectorMemoryAdvisor.META_OWNER_ID, ownerId,
                        ProductionVectorMemoryAdvisor.META_ACTIVE, true,
                        ProductionVectorMemoryAdvisor.META_TYPE, fact.type(),
                        ProductionVectorMemoryAdvisor.META_UPDATED_AT, now.toString(),
                        "memoryKey", fact.memoryKey(),
                        "sourceConversationId", fact.sourceConversationId()))
                .build();

        // ChromaVectorStore writes by document ID. Verify upsert semantics again
        // if this component is later switched to another VectorStore provider.
        this.vectorStore.write(List.of(document));
        return documentId;
    }

    public void forgetOwner(String tenantId, String ownerId) {
        validateScope(tenantId, ownerId);
        var filters = new FilterExpressionBuilder();
        this.vectorStore.delete(filters.and(
                filters.eq(ProductionVectorMemoryAdvisor.META_TENANT_ID, tenantId),
                filters.eq(ProductionVectorMemoryAdvisor.META_OWNER_ID, ownerId)).build());
    }

    private static String deterministicId(String tenantId, String ownerId, String memoryKey) {
        String source = tenantId + "\u001f" + ownerId + "\u001f" + memoryKey;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void validateScope(String tenantId, String ownerId) {
        Assert.hasText(tenantId, "tenantId must not be blank");
        Assert.hasText(ownerId, "ownerId must not be blank");
        Assert.isTrue(tenantId.equals(tenantId.trim()) && ownerId.equals(ownerId.trim()),
                "Scope identifiers must not have surrounding whitespace");
        Assert.isTrue(tenantId.indexOf('\u001f') < 0 && ownerId.indexOf('\u001f') < 0,
                "Scope identifiers must not contain the reserved separator");
        Assert.isTrue(tenantId.length() <= 128, "tenantId must not exceed 128 characters");
        Assert.isTrue(ownerId.length() <= 128, "ownerId must not exceed 128 characters");
    }

    public record MemoryFact(
            String memoryKey,
            String type,
            String content,
            String sourceConversationId) {

        private void validate() {
            Assert.hasText(this.memoryKey, "memoryKey must not be blank");
            Assert.isTrue(this.memoryKey.indexOf('\u001f') < 0,
                    "memoryKey must not contain the reserved separator");
            Assert.hasText(this.type, "type must not be blank");
            Assert.hasText(this.content, "content must not be blank");
            Assert.hasText(this.sourceConversationId, "sourceConversationId must not be blank");
            Assert.isTrue(this.memoryKey.length() <= 128, "memoryKey must not exceed 128 characters");
            Assert.isTrue(this.type.length() <= 64, "type must not exceed 64 characters");
            Assert.isTrue(this.content.length() <= 2_000, "content must not exceed 2000 characters");
        }
    }
}
