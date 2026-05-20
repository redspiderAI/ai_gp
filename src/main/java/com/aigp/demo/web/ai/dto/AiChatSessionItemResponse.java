package com.aigp.demo.web.ai.dto;

import com.aigp.demo.domain.chat.AiChatSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 用户历史会话摘要（用于会话列表） */
@Schema(description = "AI 对话会话摘要")
public record AiChatSessionItemResponse(
		@Schema(description = "会话 ID，拉取消息或继续对话时传入") Long sessionId,
		@Schema(description = "会话标题，可能为空") String title,
		@Schema(description = "提供商 key，如 mimo") String provider,
		@Schema(description = "模型名") String model,
		@Schema(description = "创建时间") LocalDateTime createdAt,
		@Schema(description = "最近更新时间") LocalDateTime updatedAt) {

	public static AiChatSessionItemResponse fromEntity(AiChatSession session) {
		return new AiChatSessionItemResponse(
				session.getId(),
				session.getTitle(),
				session.getProvider(),
				session.getModel(),
				session.getCreatedAt(),
				session.getUpdatedAt());
	}
}
