package com.feng.springailearning.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import org.springframework.ai.vectorstore.filter.Filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentIngestionServiceTests {
    @Test
    @SuppressWarnings("unchecked")
    void repeatImportUsesStableIdsAndFileUpdatesCleanOldVersionAfterWriting(@TempDir Path directory) throws Exception {
        VectorStore store = mock(VectorStore.class);
        var service = new DocumentIngestionService(store);
        Path file = directory.resolve("notes.txt");
        Files.writeString(file, "Spring AI original content");
        var resource = new FileSystemResource(file);
        service.ingestFile(resource);
        service.ingestFile(resource);
        Files.writeString(file, "Spring AI revised content");
        service.ingestFile(resource);
        ArgumentCaptor<List<Document>> chunks = ArgumentCaptor.forClass(List.class);
        var order = inOrder(store);
        for (int i = 0; i < 3; i++) {
            order.verify(store).add(chunks.capture());
            order.verify(store).delete(any(Filter.Expression.class));
        }
        var values = chunks.getAllValues();
        assertThat(values.get(0).getFirst().getId()).isEqualTo(values.get(1).getFirst().getId());
        assertThat(values.get(0).getFirst().getId()).isNotEqualTo(values.get(2).getFirst().getId());
        assertThat(values.get(0).getFirst().getMetadata().get("source_id"))
                .isEqualTo(values.get(2).getFirst().getMetadata().get("source_id"));
        assertThat(values.get(0).getFirst().getMetadata().get("ingestion_version"))
                .isNotEqualTo(values.get(2).getFirst().getMetadata().get("ingestion_version"));
    }

    @Test
    void failedWriteDoesNotDeletePreviousKnowledge() {
        VectorStore store = mock(VectorStore.class);
        doThrow(new IllegalStateException("模拟 Embedding 失败")).when(store).add(anyList());
        var service = new DocumentIngestionService(store);
        assertThatThrownBy(() -> service.ingestFile(new ByteArrayResource("Spring AI notes".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).delete(any(Filter.Expression.class));
    }

    @Test
    void rejectsMissingAndEmptyDocumentsWithoutWritingToStore() {
        VectorStore store = mock(VectorStore.class);
        var service = new DocumentIngestionService(store);
        assertThatThrownBy(() -> service.ingestFile(new FileSystemResource("nonexistent-document-for-test.txt")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingestFile(new ByteArrayResource("   ".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsReadableTextWithFilenameAndOneIngestionTimestamp() {
        VectorStore store = mock(VectorStore.class);
        var service = new DocumentIngestionService(store);
        var resource = new ByteArrayResource("Spring AI 文档入库测试。".repeat(300).getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "sample.txt"; }
        };
        service.ingestFile(resource);
        ArgumentCaptor<List<Document>> chunks = ArgumentCaptor.forClass(List.class);
        verify(store).add(chunks.capture());
        assertThat(chunks.getValue()).isNotEmpty().allSatisfy(document -> {
            assertThat(document.getText()).isNotBlank();
            assertThat(document.getMetadata()).containsEntry("filename", "sample.txt");
        });
        assertThat(chunks.getValue().stream().map(document -> document.getMetadata().get("ingest_time")).distinct()).hasSize(1);
    }
}
