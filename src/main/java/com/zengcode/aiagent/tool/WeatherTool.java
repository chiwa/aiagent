package com.zengcode.aiagent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WeatherTool {

    private final RestClient http;

    public WeatherTool(RestClient.Builder builder) {
        this.http = builder
                .baseUrl("https://api.met.no/weatherapi/locationforecast/2.0")
                .defaultHeader("User-Agent", "spring-ai-agent-demo/1.0 you@example.com")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Tool(
            name = "get_weather_by_latlon",
            description = "Get compact weather forecast (JSON) from met.no by latitude & longitude."
    )
    public String getWeatherByLatLon(double lat, double lon) {
        return http.get()
                .uri(uri -> uri.path("/compact")
                        .queryParam("lat", lat)
                        .queryParam("lon", lon)
                        .build())
                .retrieve()
                .body(String.class);
    }
}