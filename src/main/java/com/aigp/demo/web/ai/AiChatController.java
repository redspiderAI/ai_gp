package com.aigp.demo.web.ai;

import com.aigp.demo.service.AiChatService;
import com.aigp.demo.web.ai.dto.AiChatMessageListResponse;
import com.aigp.demo.web.ai.dto.AiChatRequest;
import com.aigp.demo.web.ai.dto.AiChatResponse;
import com.aigp.demo.web.ai.dto.AiChatSessionPageResponse;
import com.aigp.demo.web.security.CurrentUser;
import com.aigp.demo.web.security.JwtUserClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话：单接口对外；任务增删改查由模型通过后端工具完成，会话与消息落库。
 */
@RestController
@RequestMapping("/api/v1/ai")
@Validated
@RequiredArgsConstructor
@Tag(name = "AI 对话", description = "智能对话与助手任务管理")
@SecurityRequirement(name = "bearerAuth")
public class AiChatController {

	private final AiChatService aiChatService;

	@PostMapping("/chat")
	@Operation(summary = "发送对话消息（自动管理任务与会话记录）")
	public AiChatResponse chat(@CurrentUser JwtUserClaims user, @Valid @RequestBody AiChatRequest request) {
		return aiChatService.chat(
				user.userId(),
				request.message(),
				request.sessionId(),
				request.provider(),
				request.imageAssetIds());
	}

	@GetMapping("/chat/sessions")
	@Operation(summary = "分页列出当前用户的历史会话（含 sessionId）")
	public AiChatSessionPageResponse listSessions(
			@CurrentUser JwtUserClaims user,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return aiChatService.listUserSessions(user.userId(), page, size);
	}

	@GetMapping("/chat/sessions/{sessionId}/messages")
	@Operation(summary = "分页拉取会话历史消息（含「任务提醒」会话）")
	public AiChatMessageListResponse listMessages(
			@CurrentUser JwtUserClaims user,
			@PathVariable @Min(1) Long sessionId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
		return aiChatService.listSessionMessages(user.userId(), sessionId, page, size);
	}
}
