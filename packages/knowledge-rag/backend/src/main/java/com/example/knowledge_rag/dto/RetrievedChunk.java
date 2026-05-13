package com.example.knowledge_rag.dto;

public record RetrievedChunk(
        String id,
        String content,
        String documentId,
        double score   // 余弦距离：越小越相似（0 = 完全相同，2 = 完全相反）
) {}