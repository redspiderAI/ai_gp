package com.aigp.demo.web.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "会话消息分页列表（按时间升序）")
public record AiChatMessageListResponse(
		@Schema(description = "会话 ID") Long sessionId,
		@Schema(description = "会话标题") String sessionTitle,
		@Schema(description = "本页消息条数") int count,
		@Schema(description = "本页消息列表") List<AiChatMessageItemResponse> messages,
		@Schema(description = "页码，从 0 起") int page,
		@Schema(description = "每页条数") int size,
		@Schema(description = "该会话消息总数") long totalElements,
		@Schema(description = "总页数") int totalPages,
		@Schema(description = "是否还有下一页") boolean hasNext) {}
