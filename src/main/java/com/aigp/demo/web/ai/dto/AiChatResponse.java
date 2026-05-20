package com.aigp.demo.web.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "AI 对话响应")
public record AiChatResponse(
		@Schema(description = "助手回复正文") String reply,
		@Schema(description = "会话 ID，下轮请原样带回") Long sessionId,
		@Schema(description = "实际使用的提供商") String provider,
		@Schema(description = "实际使用的模型名") String model,
		@Schema(description = "本轮用户消息记录 ID") Long userMessageId,
		@Schema(description = "本轮助手消息记录 ID") Long assistantMessageId,
		@Schema(description = "本轮启用的能力 id 列表（如 chat、assistant_tasks）") List<String> capabilities,
		@Schema(description = "用户提及但尚未上线的能力 id 列表") List<String> unsupportedCapabilities,
		@Schema(description = "本轮用户消息附图 URL") List<String> userImageUrls,
		@Schema(description = "若本轮已生成待确认成长计划草案，则非空") AiChatPlanProposalHint planProposal) {}
