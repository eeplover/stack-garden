package com.example.knowledge_rag.controller;

import com.example.knowledge_rag.entity.Conversation;
import com.example.knowledge_rag.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationRepository conversationRepository;

    @GetMapping
    public List<Conversation> list() {
        return conversationRepository.findAllByOrderByCreatedAtDesc();
    }

    // title 用问题首句截断，由前端传入
    @PostMapping
    public ResponseEntity<Conversation> create(@RequestBody Map<String, String> body) {
        var conv = new Conversation();
        conv.setTitle(body.getOrDefault("title", "新对话"));
        return ResponseEntity.ok(conversationRepository.save(conv));
    }
}
