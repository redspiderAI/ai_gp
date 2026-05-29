package com.aigp.demo.support.llm;

import com.aigp.demo.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.aigp.demo.service.chat.AiChatPrompts;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
public class OpenAiCompatibleChatClient {

	private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

	private final ObjectMapper objectMapper;
	private final AiChatPipelineDebugLog pipelineDebugLog;

	public ChatCompletionResult chat(
			AppProperties.ChatProvider provider, List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
		return chat(provider, messages, tools, "llm");
	}

	/**
	 * 保存 LLM 设置前探测：发起一次最小 chat/completions 调用，失败时抛出带用户提示的 {@link IllegalArgumentException}。
	 */
	public void probeConnectivity(AppProperties.ChatProvider provider) {
		if (!StringUtils.hasText(provider.getBaseUrl()) || !StringUtils.hasText(provider.getModel())) {
			throw new IllegalArgumentException("baseUrl 与 model 均不能为空");
		}
		List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "ping"));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", provider.getModel().trim());
		body.put("messages", messages);
		body.put("max_tokens", 1);

		RestClient client = buildRestClient(provider);
		try {
			String responseJson = client
					.post()
					.uri("/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(String.class);
			parseResponse(responseJson);
		} catch (IllegalArgumentException ex) {
			throw ex;
		} catch (Exception ex) {
			String message = LlmConnectivityMessages.fromException(
					ex, provider.getBaseUrl(), provider.getModel(), objectMapper);
			throw new IllegalArgumentException(message, ex);
		}
	}

	private RestClient buildRestClient(AppProperties.ChatProvider provider) {
		RestClient.Builder builder =
				RestClient.builder().baseUrl(normalizeBaseUrl(provider.getBaseUrl()));
		if (StringUtils.hasText(provider.getApiKey())) {
			builder.defaultHeader("Authorization", "Bearer " + provider.getApiKey().trim());
		}
		return builder.build();
	}

	/**
	 * @param debugPhase 调试日志阶段名（如 plan / intent / execute-r1），仅 pipeline-debug 开启时输出
	 */
	public ChatCompletionResult chat(
			AppProperties.ChatProvider provider,
			List<Map<String, Object>> messages,
			List<Map<String, Object>> tools,
			String debugPhase) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", provider.getModel());
		body.put("messages", messages);
		if (tools != null && !tools.isEmpty()) {
			body.put("tools", tools);
			body.put("tool_choice", "auto");
		}

		RestClient client = buildRestClient(provider);

		String phase = debugPhase == null ? "llm" : debugPhase;
		pipelineDebugLog.llmRequest(phase, provider.getModel(), messages, tools);

		try {
			String responseJson = client
					.post()
					.uri("/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(String.class);
			ChatCompletionResult result = parseResponse(responseJson);
			pipelineDebugLog.llmResponse(phase, result, responseJson);
			return result;
		} catch (RestClientResponseException ex) {
			String detail = ex.getResponseBodyAsString();
			if (detail != null && detail.length() > 500) {
				detail = detail.substring(0, 500);
			}
			pipelineDebugLog.step(phase, "LLM 调用失败 status=%s body=%s", ex.getStatusCode(), detail);
			log.warn("LLM 调用失败 phase={} status={} body={}", phase, ex.getStatusCode(), detail);
			throw new IllegalStateException(AiChatPrompts.LLM_CALL_FAILED_USER_MESSAGE, ex);
		}
	}

	private ChatCompletionResult parseResponse(String responseJson) {
		try {
			JsonNode root = objectMapper.readTree(responseJson);
			JsonNode message = root.path("choices").path(0).path("message");
			String content = message.path("content").isNull() ? null : message.path("content").asText();
			JsonNode rcNode = message.path("reasoning_content");
			String reasoningContent = null;
			if (!rcNode.isMissingNode() && !rcNode.isNull()) {
				reasoningContent = rcNode.isTextual() ? rcNode.asText() : rcNode.toString();
			}
			List<ChatCompletionResult.ToolCallPayload> toolCalls = new ArrayList<>();
			JsonNode toolCallsNode = message.path("tool_calls");
			if (toolCallsNode.isArray()) {
				for (JsonNode tc : toolCallsNode) {
					String id = tc.path("id").asText();
					JsonNode fn = tc.path("function");
					toolCalls.add(new ChatCompletionResult.ToolCallPayload(
							id, fn.path("name").asText(), fn.path("arguments").asText("")));
				}
			}
			JsonNode usage = root.path("usage");
			Integer promptTokens = usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt();
			Integer completionTokens =
					usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt();
			return new ChatCompletionResult(content, reasoningContent, toolCalls, promptTokens, completionTokens);
		} catch (Exception e) {
			log.warn("解析模型响应失败: {}", e.getMessage());
			throw new IllegalStateException(AiChatPrompts.LLM_CALL_FAILED_USER_MESSAGE, e);
		}
	}

	private static String normalizeBaseUrl(String baseUrl) {
		String u = baseUrl == null ? "" : baseUrl.trim();
		while (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		return u;
	}
}
