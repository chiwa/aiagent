package com.zengcode.aiagent.controller;

import com.zengcode.aiagent.service.AgentService;
import com.zengcode.aiagent.tool.WeatherTool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
public class ChatController {
    private final AgentService agentService;
    private final WeatherTool weatherTool;
    public ChatController(AgentService agentService, WeatherTool weatherTool) {
        this.agentService = agentService;
        this.weatherTool = weatherTool;
    }
    @GetMapping("/api/ask")
    public Map<String, String> ask(@RequestParam String question) {
        String answer = agentService.ask(question);
        return Map.of("question", question, "answer", answer);
    }

    @GetMapping("/api/test")
    public Map<String, String> test(@RequestParam String question) {
        String answer = weatherTool.getWeatherByLatLon(13.75,100.50);
        return Map.of("question", question, "answer", answer);
    }
}
