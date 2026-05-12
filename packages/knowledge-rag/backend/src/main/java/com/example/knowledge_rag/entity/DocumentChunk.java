package com.example.knowledge_rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_chunks")
@Data
@NoArgsConstructor
public class DocumentChunk {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "content_hash")
    private String contentHash;    // SHA-256，防止重复 embedding

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}