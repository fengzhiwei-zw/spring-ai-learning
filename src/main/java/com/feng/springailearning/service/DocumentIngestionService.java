package com.feng.springailearning.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;

@Service
@Profile("!local")
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private final VectorStore knowledgeVectorStore;

    public DocumentIngestionService(@Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore) {
        this.knowledgeVectorStore = knowledgeVectorStore;
    }

    /**
     * 从本地文件/classpath 加载并入库
     */
    public synchronized void ingestFile(Resource resource) {
        Assert.notNull(resource, "文档资源不能为空");
        Assert.isTrue(resource.exists() && resource.isReadable(), "文档资源不存在或不可读");
        // 1. 读取文档（支持 PDF、Word、TXT、MD 等多种格式）
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        // 2. 分块（非常重要！）
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)          // 每块目标 token 数
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();

        List<Document> splitChunks = splitter.apply(documents).stream()
                .filter(document -> StringUtils.hasText(document.getText()))
                .toList();
        if (splitChunks.isEmpty()) {
            throw new IllegalArgumentException("文档未包含可入库的文本");
        }

        // 3. 使用内容版本和稳定 ID。重复导入会 upsert，而不是不断制造重复片段。
        String version = hash(splitChunks.stream().map(doc -> doc.getText().length() + ":" + doc.getText())
                .collect(java.util.stream.Collectors.joining()));
        String sourceId;
        try {
            sourceId = hash(resource.getURI().normalize().toString());
        } catch (IOException exception) {
            // 无稳定地址的内存资源只能按内容识别，不能假设同名文件就是同一文档。
            sourceId = version;
        }
        final String stableSourceId = sourceId;
        String ingestTime = LocalDateTime.now().toString();
        List<Document> chunks = java.util.stream.IntStream.range(0, splitChunks.size()).mapToObj(index -> {
            Document original = splitChunks.get(index);
            Map<String, Object> metadata = new HashMap<>(original.getMetadata());
            metadata.put("filename", resource.getFilename() != null ? resource.getFilename() : "");
            metadata.put("ingest_time", ingestTime);
            metadata.put("source_id", stableSourceId);
            metadata.put("ingestion_version", version);
            String id = UUID.nameUUIDFromBytes((stableSourceId + ":" + version + ":" + index)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            return Document.builder().id(id).text(original.getText()).metadata(metadata).build();
        }).toList();

        // 4. 写入向量库（自动调用 EmbeddingModel）
        knowledgeVectorStore.add(chunks);

        // 新版本写成功后才删除同一来源的旧版本；写入失败不会先清空已有知识。
        var filters = new FilterExpressionBuilder();
        knowledgeVectorStore.delete(filters.and(filters.eq("source_id", stableSourceId),
                filters.ne("ingestion_version", version)).build());

        log.info("document.ingested chunks={}", chunks.size());
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 从 classpath 加载示例
     */
    public void ingestFromClasspath(String classpathLocation) {
        Resource resource = new ClassPathResource(classpathLocation);
        ingestFile(resource);
    }

    /**
     * 从本地文件系统加载
     */
    public void ingestFromFileSystem(String filePath) {
        Resource resource = new FileSystemResource(filePath);
        ingestFile(resource);
    }
}
