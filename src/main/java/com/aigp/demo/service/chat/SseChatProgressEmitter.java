package com.aigp.demo.service.chat;

import com.aigp.demo.web.ai.dto.AiChatProgressEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 将流水线进度写入 {@link SseEmitter}（{@code event: progress}）。
 */
public class SseChatProgressEmitter implements ChatProgressEmitter {

	private static final Logger log = LoggerFactory.getLogger(SseChatProgressEmitter.class);

	private final SseEmitter sseEmitter;
	private final ObjectMapper objectMapper;
	private final String traceId;
	private Long sessionId;

	public SseChatProgressEmitter(SseEmitter sseEmitter, ObjectMapper objectMapper, String traceId) {
		this.sseEmitter = sseEmitter;
		this.objectMapper = objectMapper;
		this.traceId = traceId;
	}

	@Override
	public void emit(AiChatProgressCode code) {
		sendProgress(
				code.name(),
				AiChatProgressMessages.message(code),
				code.phase(),
				null,
				null,
				null);
	}

	@Override
	public void emitTool(String toolName, int executeRound, int toolIndexInRound) {
		var code = AiChatProgressMessages.codeForTool(toolName);
		if (code.isEmpty()) {
			return;
		}
		AiChatProgressCode c = code.get();
		sendProgress(
				c.name(),
				AiChatProgressMessages.message(c),
				c.phase(),
				toolName,
				executeRound,
				toolIndexInRound);
	}

	@Override
	public void bindSession(Long sessionId) {
		this.sessionId = sessionId;
		emit(AiChatProgressCode.SESSION_READY);
	}

	@Override
	public boolean isActive() {
		return true;
	}

	private void sendProgress(
			String code,
			String message,
			String phase,
			String tool,
			Integer round,
			Integer toolIndex) {
		AiChatProgressEvent event =
				AiChatProgressEvent.of(code, message, phase, sessionId, traceId, tool, round, toolIndex);
		try {
			String json = objectMapper.writeValueAsString(event);
			sseEmitter.send(SseEmitter.event().name("progress").data(json));
		} catch (JsonProcessingException e) {
			log.warn("序列化进度事件失败 code={}: {}", code, e.getMessage());
		} catch (IOException e) {
			log.debug("SSE 进度发送失败（客户端可能已断开） code={}: {}", code, e.getMessage());
		}
	}
}
