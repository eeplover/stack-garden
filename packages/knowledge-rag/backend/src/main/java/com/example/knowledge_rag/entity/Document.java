package com.example.knowledge_rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity           // 声明这是 JPA 管理的实体，对应数据库里的一张表
@Table(name = "documents") // 显式指定表名，不写则默认用类名小写。推荐显式写明。
@Data             // Lombok：自动生成 getter / setter / toString / equals / hashCode
@NoArgsConstructor // Lombok：JPA 强制要求实体必须有无参构造函数，Lombok 帮你生成
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    // 为什么用 UUID 而不是自增 Long？
    // → 分布式场景下不会冲突（多实例同时插入）
    // → 不暴露业务信息（用户无法通过 ID 猜出总数据量）
    // → 可以在应用层提前生成 ID，无需等待数据库自增
    private String id;

    @Column(nullable = false)
    private String filename;      // 服务器存储的实际文件名：UUID_原文件名，防止重名冲突

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;  // 用户上传时的原始文件名，只用于展示

    @Column(name = "file_type")
    private String fileType;

    @Column(nullable = false)
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    // 必须用 EnumType.STRING，不要用默认的 EnumType.ORDINAL（存数字 0、1、2...）
    // 原因：ORDINAL 依赖枚举的声明顺序，一旦中间插入新枚举值，
    //       历史数据的数字含义就错位了，导致静默数据污染，极难排查。
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @CreationTimestamp
    // Hibernate 注解：INSERT 时自动填入当前时间，不需要手动 setCreatedAt()
    @Column(name = "created_at", updatable = false)
    // updatable = false：生成 UPDATE SQL 时忽略这个字段，防止创建时间被意外改写
    private LocalDateTime createdAt;

    @UpdateTimestamp
    // Hibernate 注解：每次 UPDATE 时自动更新为当前时间
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}