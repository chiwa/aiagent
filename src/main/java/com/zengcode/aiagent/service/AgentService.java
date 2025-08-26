package com.zengcode.aiagent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
    private final ChatClient chatClient;
    public AgentService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }
    public String ask(String question) {
        return chatClient
                .prompt()
                .user(question)
                .call()
                .content();
    }
}
