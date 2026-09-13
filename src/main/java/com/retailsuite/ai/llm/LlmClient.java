package com.retailsuite.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsuite.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 大模型客户端（OpenAI 兼容协议）。
 *
 * 三个刻意的设计：
 * 1) **可选**：没配 API Key 时 configured()==false，上层自动走本地规则解析——
 *    这样"演示环境不依赖外部服务也能跑"，不会因为没配额就整站不可用；
 * 2) 失败即降级：任何异常都返回 Optional.empty()，由调用方决定回退策略，绝不把异常抛给收银链路；
 * 3) 只做"文本 → JSON"这一件事，业务校验、库存检查、权限都在 Java 侧完成——
 *    模型输出永远不被直接信任（这也是"AI 草稿必须人工确认"的前提）。
 */
@Slf4j
@Component
public class LlmClient {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public LlmClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean configured() {
        return properties.getAi().isEnabled() && properties.getAi().llmConfigured();
    }

    /** 让模型输出 JSON（jsonMode 下要求返回 json_object）。 */
    public Optional<String> chatJson(String systemPrompt, String userPrompt) {
        if (!configured()) {
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getAi().getModel());
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            body.put("messages", messages);
            body.put("temperature", 0.0);
            body.put("max_tokens", 1024);
            body.put("response_format", Map.of("type", "json_object"));

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("模型调用失败 HTTP {}：{}", response.statusCode(), truncate(response.body()));
                return Optional.empty();
            }
            String text = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            log.info("模型调用成功 耗时={}ms 输出长度={}", System.currentTimeMillis() - start, text.length());
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("模型调用异常（将退回本地规则解析）：{}", e.toString());
            return Optional.empty();
        }
    }

    private String endpoint() {
        String base = properties.getAi().getBaseUrl() == null ? "" : properties.getAi().getBaseUrl().trim()
                .replaceAll("/+$", "");
        return base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
