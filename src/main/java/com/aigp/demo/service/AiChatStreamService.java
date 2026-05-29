package com.aigp.demo.service;

import com.aigp.demo.service.chat.AiChatConversationPipeline;
import com.aigp.demo.service.chat.AiChatPrompts;
import com.aigp.demo.service.chat.SseChatProgressEmitter;
import com.aigp.demo.support.llm.AiChatPipelineDebugLog;
import com.aigp.demo.web.ai.dto.AiChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对话 SSE 流式：处理过程中推送固定进度话术，结束时推送 {@code event: done}。
 */
@Service
@RequiredArgsConstructor
public class AiChatStreamService {

	private static final Logger log = LoggerFactory.getLogger(AiChatStreamService.class);
	/** SSE 超时：覆盖规划 + 多轮工具 + 落库（毫秒） */
	private static final long SSE_TIMEOUT_MS = 300_000L;

	private final AiChatConversationPipeline conversationPipeline;
	private final AiChatPipelineDebugLog pipelineDebugLog;
	private final ObjectMapper objectMapper;

	/**
	 * 发起一轮流式对话。
	 *
	 * @param userId 当前登录用户
	 * @param userMessage 用户输入
	 * @param sessionId 可选会话 id
	 * @param providerOverride 可选模型提供商
	 * @param imageAssetIds 可选附图
	 */
	public SseEmitter streamChat(
			Long userId,
			String userMessage,
			Long sessionId,
			String providerOverride,
			List<Long> imageAssetIds) {
		SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
		String traceId = UUID.randomUUID().toString().replace("-", "");
		SseChatProgressEmitter progressEmitter = new SseChatProgressEmitter(emitter, objectMapper, traceId);

		Thread.startVirtualThread(() ->
				runStream(userId, userMessage, sessionId, providerOverride, imageAssetIds, emitter, progressEmitter, traceId));

		emitter.onTimeout(emitter::complete);
		emitter.onError(ex -> log.debug("SSE 连接异常 traceId={}: {}", traceId, ex.getMessage()));
		return emitter;
	}

	private void runStream(
			Long userId,
			String userMessage,
			Long sessionId,
			String providerOverride,
			List<Long> imageAssetIds,
			SseEmitter emitter,
			SseChatProgressEmitter progressEmitter,
			String traceId) {
		pipelineDebugLog.beginConversationTrace(userId, sessionId, userMessage);
		try {
			AiChatRequestContext.setUserMessage(userMessage);
			AiChatRequestContext.setMessageImageAssetIds(normalizeImageAssetIds(imageAssetIds));
			try {
				AiChatResponse response = conversationPipeline.processRound(
						userId, userMessage, sessionId, providerOverride, imageAssetIds, progressEmitter);
				String doneJson = objectMapper.writeValueAsString(response);
				emitter.send(SseEmitter.event().name("done").data(doneJson));
				emitter.complete();
			} finally {
				AiChatRequestContext.clear();
			}
		} catch (Exception ex) {
			pipelineDebugLog.endConversationTrace(false, "error: " + ex.getMessage());
			log.warn("流式对话失败 traceId={}: {}", traceId, ex.getMessage());
			try {
				emitter.send(SseEmitter.event().name("error").data(safeErrorJson(ex)));
			} catch (IOException ignored) {
				// 客户端已断开
			}
			emitter.completeWithError(ex);
		}
	}

	private String safeErrorJson(Exception ex) {
		try {
			return objectMapper.writeValueAsString(
					java.util.Map.of("type", "error", "message", userSafeMessage(ex)));
		} catch (JsonProcessingException e) {
			return "{\"type\":\"error\",\"message\":\"服务繁忙，请稍后再试\"}";
		}
	}

	private static String userSafeMessage(Exception ex) {
		String msg = ex.getMessage();
		if (msg == null || msg.isBlank()) {
			return "服务繁忙，请稍后再试";
		}
		if (AiChatPrompts.LLM_CALL_FAILED_USER_MESSAGE.equals(msg)
				|| AiChatPrompts.EXECUTION_MAX_TOOL_ROUNDS_EXCEEDED.equals(msg)) {
			return msg;
		}
		if (msg.length() > 120) {
			return "服务繁忙，请稍后再试";
		}
		return msg;
	}

	private static List<Long> normalizeImageAssetIds(List<Long> imageAssetIds) {
		if (imageAssetIds == null || imageAssetIds.isEmpty()) {
			return List.of();
		}
		return imageAssetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
	}
}
