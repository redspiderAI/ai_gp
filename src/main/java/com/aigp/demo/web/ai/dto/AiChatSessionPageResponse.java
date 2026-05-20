package com.aigp.demo.web.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 当前用户的历史会话分页列表 */
@Schema(description = "AI 对话会话分页列表")
public record AiChatSessionPageResponse(
		@Schema(description = "本会话页数据，按 updatedAt 降序") List<AiChatSessionItemResponse> items,
		@Schema(description = "页码，从 0 起") int page,
		@Schema(description = "每页条数") int size,
		@Schema(description = "符合条件的会话总数") long totalElements,
		@Schema(description = "总页数") int totalPages,
		@Schema(description = "是否还有下一页") boolean hasNext) {}
