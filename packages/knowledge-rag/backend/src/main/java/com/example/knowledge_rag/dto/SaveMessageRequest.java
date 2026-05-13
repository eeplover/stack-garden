package com.example.knowledge_rag.dto;

import com.example.knowledge_rag.entity.MessageRole;

public record SaveMessageRequest(MessageRole role, String content, String sources) {}
