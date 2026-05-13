package com.example.knowledge_rag.repository;

import com.example.knowledge_rag.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {
    // 按会话 ID 查历史消息，按时间正序（便于前端按顺序渲染对话）
    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}