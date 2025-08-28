package com.zengcode.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengcode.aiagent.tool.WeatherTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient chat;
    private final WeatherTool weatherTool;
    private final ObjectMapper om = new ObjectMapper();

    // เมืองที่รองรับแบบง่าย ๆ (เพิ่มได้)
    private static final Map<String, double[]> CITY_LATLON = Map.of(
            "กรุงเทพ", new double[]{13.75, 100.50},
            "bangkok", new double[]{13.75, 100.50},
            "เชียงใหม่", new double[]{18.79, 98.98}
    );

    public AgentService(ChatClient.Builder builder, WeatherTool weatherTool) {
        this.chat = builder
                .defaultOptions(ToolCallingChatOptions.builder()
                        .temperature(0.0)   // ทำให้ deterministic
                        .build())
                .build();
        this.weatherTool = weatherTool;
    }

    /** keyword ง่าย ๆ ว่าถามเรื่องอากาศไหม */
    private boolean isWeatherQuery(String m) {
        String s = m.toLowerCase();
        return s.contains("อากาศ") || s.contains("พยากรณ์")
                || s.contains("ฝน")   || s.contains("ร้อน") || s.contains("ลม")
                || s.matches(".*\\b(weather|forecast|rain|wind|temp(?:erature)?)\\b.*");
    }

    public String ask(String userMessage) {
        log.info("[ask] userMessage={}", userMessage);

        if (isWeatherQuery(userMessage)) {
            return answerWeather(userMessage, detectCity(userMessage));
        }

        // ให้ LLM ช่วยตัดสิน intent (กันหลุดด้วย parse เข้ม)
        String decisionJson;
        try {
            decisionJson = chat.prompt()
                    .system("""
                        ตอบเป็น JSON อย่างเดียว:
                        {"intent":"weather|chitchat","city":"<ชื่อเมืองหรือว่าง>"}
                        - ถ้าถามสภาพอากาศ/พยากรณ์/ฝน/ลม → intent=weather
                        - ถ้าไม่ใช่ → intent=chitchat
                        ห้ามพิมพ์คำอื่นนอกจาก JSON
                        """)
                    .user(userMessage)
                    .options(ToolCallingChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
        } catch (Exception callEx) {
            log.warn("[ask] LLM decision call failed: {}", callEx.toString());
            decisionJson = "";
        }

        log.debug("[ask] decisionJson={}", decisionJson);

        String intent = "chitchat";
        String city = "";
        try {
            JsonNode n = om.readTree(decisionJson);
            if ("weather".equalsIgnoreCase(n.path("intent").asText(""))) {
                intent = "weather";
            }
            city = n.path("city").asText("");
        } catch (Exception parseEx) {
            log.warn("[ask] parse decision JSON failed: {}", parseEx.toString());
        }

        if ("weather".equals(intent)) {
            return answerWeather(userMessage, city);
        }

        // ไม่ใช่เรื่องอากาศ → คุยปกติ
        return chat.prompt()
                .system("คุณคือผู้ช่วยภาษาไทย ตอบสั้น กระชับ และสุภาพ")
                .user(userMessage)
                .options(ToolCallingChatOptions.builder().temperature(0.3).build())
                .call()
                .content();
    }

    private String answerWeather(String userMessage, String cityHint) {
        double[] latlon = pickLatLon(userMessage, cityHint);
        double lat = latlon[0], lon = latlon[1];
        log.info("[weather] cityHint='{}', resolved lat={}, lon={}", cityHint, lat, lon);

        String json;
        try {
            json = weatherTool.getWeatherByLatLon(lat, lon);
            log.info("[weatherTool] lat={}, lon={}, bytes={}", lat, lon, (json != null ? json.length() : 0));
        } catch (Exception ex) {
            log.error("[weatherTool] call failed: {}", ex.toString());
            return "ขออภัย ระบบเรียกข้อมูลอากาศไม่ได้ตอนนี้ ลองใหม่อีกครั้งนะครับ";
        }

        // --- ดึงค่า "วันนี้" แบบ deterministic (ไม่ให้ LLMเดา) ---
        try {
            var root = om.readTree(json);
            var times = root.path("properties").path("timeseries");

            var todayUtc = java.time.LocalDate.now(ZoneOffset.UTC);

            Double temp = null, wind = null, rain = null;
            String timePicked = null;

            for (JsonNode t : times) {
                String iso = t.path("time").asText();
                if (iso == null || iso.isBlank()) continue;

                OffsetDateTime instant = OffsetDateTime.parse(iso);
                if (!instant.toLocalDate().equals(todayUtc)) continue; // เฉพาะวันนี้ (UTC)

                var details = t.path("data").path("instant").path("details");
                if (details.isMissingNode()) continue;

                // เอา "ช็อตแรกของวันนี้" สำหรับเดโม่
                if (details.path("air_temperature").isNumber()) {
                    temp = details.get("air_temperature").asDouble();
                }
                if (details.path("wind_speed").isNumber()) {
                    wind = details.get("wind_speed").asDouble();
                }

                var next1h = t.path("data").path("next_1_hours").path("details");
                if (next1h.isObject() && next1h.path("precipitation_amount").isNumber()) {
                    rain = next1h.get("precipitation_amount").asDouble();
                }

                timePicked = iso;
                break;
            }

            // ประกอบคำตอบแบบไม่เดา
            var sb = new StringBuilder("สรุปอากาศวันนี้");
            sb.append(" (").append(lat).append(",").append(lon).append(")");
            if (timePicked != null) sb.append(" @").append(timePicked);

            sb.append(" - ");
            boolean wrote = false;

            if (temp != null) { sb.append(String.format("อุณหภูมิ %.1f°C", temp)); wrote = true; }
            if (rain != null) { sb.append(wrote ? ", " : "").append(String.format("ฝน %.1f mm/h", rain)); wrote = true; }
            if (wind != null) { sb.append(wrote ? ", " : "").append(String.format("ลม %.1f m/s", wind)); wrote = true; }

            if (!wrote) sb.append("ไม่มีข้อมูลเพียงพอสำหรับวันนี้");

            String plain = sb.toString();
            log.debug("[weather] plain='{}'", plain);

            // (ทางเลือก) ให้ LLM รีไรต์ให้อ่านลื่นขึ้น แต่ "ห้ามเปลี่ยนตัวเลข"
            String polished;
            try {
                polished = chat.prompt()
                        .system("""
                            คุณคือผู้ช่วยภาษาไทย รีไรต์ข้อความให้สละสลวยเล็กน้อยแต่ต้องคงตัวเลขเดิมทุกตัว
                            ห้ามสมมติ/เพิ่มข้อมูลใหม่ ถ้าข้อมูลไม่ครบให้คงข้อความเดิม
                            """)
                        .user(plain)
                        .options(ToolCallingChatOptions.builder().temperature(0.0).build())
                        .call()
                        .content();
            } catch (Exception ex) {
                log.warn("[weather] polish failed: {}", ex.toString());
                polished = null;
            }

            return (polished == null || polished.isBlank()) ? plain : polished;

        } catch (Exception parseEx) {
            log.error("[weather] parse JSON failed: {}", parseEx.toString());
            return "สรุปอากาศวันนี้ไม่สำเร็จ (รูปแบบข้อมูลเปลี่ยน) - ลองใหม่อีกครั้งครับ";
        }
    }

    private String detectCity(String msg) {
        String s = msg.toLowerCase();
        if (s.contains("เชียงใหม่")) return "เชียงใหม่";
        if (s.contains("กรุงเทพ") || s.contains("bangkok")) return "กรุงเทพ";
        return "";
    }

    private double[] pickLatLon(String userMessage, String cityHint) {
        String all = (userMessage + " " + cityHint).toLowerCase();
        for (String key : CITY_LATLON.keySet()) {
            if (all.contains(key)) return CITY_LATLON.get(key);
        }
        // default = กรุงเทพ
        return CITY_LATLON.get("กรุงเทพ");
    }
}