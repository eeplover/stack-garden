package com.example.knowledge_rag.controller;

import com.example.knowledge_rag.dto.FeedbackRequest;
import com.example.knowledge_rag.dto.SaveMessageRequest;
import com.example.knowledge_rag.entity.Message;
import com.example.knowledge_rag.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /** 查询会话历史：前端对话列表初始化时调用。 */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<Message> listMessages(@PathVariable String conversationId) {
        return messageService.listByConversation(conversationId);
    }

    /** 前端流式回答结束后，将用户消息和助手消息分两次 POST 保存到数据库。 */
    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<Message> saveMessage(
            @PathVariable String conversationId,
            @RequestBody SaveMessageRequest req) {
        return ResponseEntity.ok(
                messageService.save(conversationId, req.role(), req.content(), req.sources()));
    }

    /**
     * 点赞 / 点踩接口。
     * PUT 语义：对已有资源做部分更新，比 PATCH 更常见于简单字段覆盖场景。
     */
    @PutMapping("/messages/{id}/feedback")
    public void feedback(@PathVariable String id, @RequestBody FeedbackRequest req) {
        messageService.updateFeedback(id, req.type(), req.comment());
    }
}
