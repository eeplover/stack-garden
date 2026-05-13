package com.example.knowledge_rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "conversation_id")
    private String conversationId;

    @Enumerated(EnumType.STRING)
    private MessageRole role;    // USER / ASSISTANT

    @Column(columnDefinition = "TEXT")
    private String content;

    // @JdbcTypeCode(SqlTypes.JSON)：告诉 Hibernate 6 以 JSON 类型绑定参数，
    // 否则 PostgreSQL 收到的是 varchar，会报 "column is of type jsonb" 类型不匹配错误。
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String sources;      // JSON 序列化的 chunks

    @Enumerated(EnumType.STRING)
    private FeedbackType feedback;  // LIKED / DISLIKED / null

    @Column(columnDefinition = "TEXT")
    private String feedbackComment;

    @CreationTimestamp
    private LocalDateTime createdAt;
}