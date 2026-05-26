package com.aigp.demo.web.ai.dto;

import com.aigp.demo.domain.chat.AiChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "对话消息")
public record AiChatMessageItemResponse(
		@Schema(description = "消息 ID") Long id,
		@Schema(description = "角色：USER / ASSISTANT") String role,
		@Schema(description = "文本内容") String content,
		@Schema(description = "附图 URL 列表（仅 USER 可能有）") List<String> imageUrls,
		@Schema(
						description =
								"仅 ASSISTANT 可能有：与 POST /ai/chat 的 roundAction 相同枚举；历史/定时消息为 null")
				String roundAction,
		@Schema(description = "创建时间") LocalDateTime createdAt) {

	public static AiChatMessageItemResponse fromEntity(AiChatMessage m, List<String> imageUrls) {
		return new AiChatMessageItemResponse(
				m.getId(),
				m.getRole().name(),
				m.getContent(),
				imageUrls == null ? List.of() : imageUrls,
				m.getRoundAction(),
				m.getCreatedAt());
	}
}
