package com.zengcode.aiagent.controller;

import com.zengcode.aiagent.service.AgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
public class ChatController {
    private final AgentService agentService;
    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }
    @GetMapping("/api/ask")
    public Map<String, String> ask(@RequestParam String question) {
        String answer = agentService.ask(question);
        return Map.of("question", question, "answer", answer);
    }
}
