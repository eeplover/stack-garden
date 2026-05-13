package com.example.knowledge_rag.service;

import com.example.knowledge_rag.entity.FeedbackType;
import com.example.knowledge_rag.entity.Message;
import com.example.knowledge_rag.entity.MessageRole;
import com.example.knowledge_rag.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;

    /** 保存一条消息（USER 或 ASSISTANT），返回带 id / createdAt 的完整记录。 */
    public Message save(String conversationId, MessageRole role,
                        String content, String sourcesJson) {
        var msg = new Message();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSources(sourcesJson);
        return messageRepository.save(msg);
    }

    /** 查询某会话的全部历史消息，按创建时间升序。 */
    public List<Message> listByConversation(String conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * 更新用户对某条消息的反馈（点赞 / 点踩 + 可选评论）。
     * 消息不存在时抛出 IllegalArgumentException，由全局异常处理器返回 404。
     */
    public void updateFeedback(String messageId, FeedbackType type, String comment) {
        var msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        msg.setFeedback(type);
        msg.setFeedbackComment(comment);
        messageRepository.save(msg);
    }
}
