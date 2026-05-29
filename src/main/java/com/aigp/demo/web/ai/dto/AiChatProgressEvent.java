package com.aigp.demo.web.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** SSE {@code event: progress} 载荷。 */
@Schema(description = "AI 对话进行中进度事件")
public record AiChatProgressEvent(
		@Schema(description = "固定为 progress") String type,
		@Schema(description = "进度码，如 TOOL_CREATE_TASK") String code,
		@Schema(description = "用户可见固定文案") String message,
		@Schema(description = "阶段：ROUTING / PREPARING / EXECUTING") String phase,
		@Schema(description = "会话 ID") Long sessionId,
		@Schema(description = "本轮追踪 ID，与调试日志一致") String traceId,
		@Schema(description = "当前执行的工具名，可空") String tool,
		@Schema(description = "执行环轮次，从 1 起") Integer round,
		@Schema(description = "该轮内工具序号，从 1 起") Integer toolIndex) {

	public static AiChatProgressEvent of(
			String code,
			String message,
			String phase,
			Long sessionId,
			String traceId,
			String tool,
			Integer round,
			Integer toolIndex) {
		return new AiChatProgressEvent("progress", code, message, phase, sessionId, traceId, tool, round, toolIndex);
	}
}
