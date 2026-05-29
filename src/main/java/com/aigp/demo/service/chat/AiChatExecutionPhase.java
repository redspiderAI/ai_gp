package com.aigp.demo.service.chat;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.aigp.demo.service.AiChatToolExecutor;
import com.aigp.demo.support.llm.AiChatPipelineDebugLog;
import com.aigp.demo.support.llm.ChatCompletionResult;
import com.aigp.demo.support.llm.OpenAiCompatibleChatClient;
import com.aigp.demo.web.ai.dto.AiChatPlanProposalHint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话流水线阶段 3：执行任务（调用大模型 + 多轮工具；过程不落库）。
 */
@Component
@RequiredArgsConstructor
public class AiChatExecutionPhase {

	private static final Logger log = LoggerFactory.getLogger(AiChatExecutionPhase.class);

	private final AppProperties appProperties;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiChatToolExecutor aiChatToolExecutor;
	private final AiChatPipelineDebugLog pipelineDebugLog;
	private final ObjectMapper objectMapper;
	private final AiChatResponseFinalizer responseFinalizer;

	/**
	 * 执行阶段结果：面向用户的正文、可选计划草案、本轮动作类型。
	 */
	public record Result(String text, AiChatPlanProposalHint planProposal, AiChatRoundAction roundAction) {}

	/**
	 * 根据是否挂载工具，执行单轮或多轮（含 tool_calls）对话。
	 *
	 * @param userId 当前用户
	 * @param providerConfig 执行用模型配置（含附图时可能为 VLM）
	 * @param messages 已含 system、历史、本轮 user 的消息列表（可变）
	 * @param tools 本轮允许的工具定义，空则纯文本一轮
	 * @param toolGuard 任务变更兜底
	 */
	public Result run(
			Long userId,
			AppProperties.ChatProvider providerConfig,
			List<Map<String, Object>> messages,
			List<Map<String, Object>> tools,
			AiChatExecutionToolGuard toolGuard,
			ChatProgressEmitter progress) {
		ChatProgressEmitter emitter = progress == null ? NoopChatProgressEmitter.INSTANCE : progress;
		if (tools == null || tools.isEmpty()) {
			if (emitter.isActive()) {
				emitter.emit(AiChatProgressCode.GENERATING);
			}
			ChatCompletionResult result =
					openAiCompatibleChatClient.chat(providerConfig, messages, null, "execute");
			return new Result(
					responseFinalizer.finalizeAssistantText(result.content()),
					null,
					AiChatRoundAction.CHAT_ONLY);
		}
		AiChatPlanProposalHint planProposal = null;
		AiChatRoundActionTracker roundActionTracker = new AiChatRoundActionTracker(objectMapper);
		int maxRounds = Math.max(1, appProperties.getChat().getMaxToolRounds());
		for (int round = 0; round < maxRounds; round++) {
			String phase = "execute-r" + (round + 1);
			pipelineDebugLog.step(phase, "开始第 %s/%s 轮工具对话", round + 1, maxRounds);
			if (emitter.isActive()) {
				emitter.emit(AiChatProgressCode.GENERATING);
			}
			ChatCompletionResult result = openAiCompatibleChatClient.chat(providerConfig, messages, tools, phase);
			if (!result.hasToolCalls()) {
				if (emitter.isActive() && round > 0) {
					emitter.emit(AiChatProgressCode.SYNTHESIZING);
				}
				AiChatRoundAction roundAction = roundActionTracker.get();
				if (toolGuard.shouldRetryMissingMutationTools(result.content(), roundAction)) {
					toolGuard.markMutationRetried();
					pipelineDebugLog.step(phase, "变更未落库却口头承诺，追加一轮强制补调（变更）");
					messages.add(Map.of("role", "system", "content", toolGuard.retryMutationNudge()));
					continue;
				}
				if (toolGuard.shouldRetryMissingQueryTools(result.content(), roundAction)) {
					toolGuard.markQueryRetried();
					pipelineDebugLog.step(phase, "未 list_tasks 却罗列待办，追加一轮强制补调（查询）");
					messages.add(Map.of("role", "system", "content", toolGuard.retryQueryNudge()));
					continue;
				}
				pipelineDebugLog.step(phase, "无 tool_calls，结束执行环");
				String text = responseFinalizer.finalizeAssistantText(result.content());
				if (toolGuard.needsFallbackAfterMissingMutation(text, roundAction)) {
					text = AiChatPrompts.FALLBACK_MUTATION_NOT_PERSISTED;
				} else if (toolGuard.needsFallbackAfterMissingQuery(text, roundAction)) {
					text = AiChatPrompts.FALLBACK_QUERY_NOT_PERSISTED;
				}
				return new Result(text, planProposal, roundAction);
			}

			Map<String, Object> assistantMsg = new LinkedHashMap<>();
			assistantMsg.put("role", "assistant");
			if (StringUtils.hasText(result.content())) {
				assistantMsg.put("content", result.content());
			}
			List<Map<String, Object>> toolCallMaps = new ArrayList<>();
			for (ChatCompletionResult.ToolCallPayload tc : result.toolCalls()) {
				Map<String, Object> fn = new LinkedHashMap<>();
				fn.put("name", tc.name());
				fn.put("arguments", tc.argumentsJson());
				Map<String, Object> call = new LinkedHashMap<>();
				call.put("id", tc.id());
				call.put("type", "function");
				call.put("function", fn);
				toolCallMaps.add(call);
			}
			assistantMsg.put("tool_calls", toolCallMaps);
			if (StringUtils.hasText(result.reasoningContent())) {
				assistantMsg.put("reasoning_content", result.reasoningContent());
			}
			messages.add(assistantMsg);

			int toolIndex = 0;
			for (ChatCompletionResult.ToolCallPayload tc : result.toolCalls()) {
				toolIndex++;
				if (emitter.isActive()) {
					emitter.emitTool(tc.name(), round + 1, toolIndex);
				}
				roundActionTracker.record(tc.name(), tc.argumentsJson());
				String toolResult =
						aiChatToolExecutor.execute(userId, tc.name(), tc.argumentsJson(), phase, round + 1);
				if ("propose_growth_plan".equals(tc.name())) {
					AiChatPlanProposalHint hint = parsePlanProposalHint(toolResult);
					if (hint != null) {
						planProposal = hint;
					}
				}
				messages.add(Map.of("role", "tool", "tool_call_id", tc.id(), "content", toolResult));
			}
		}
		pipelineDebugLog.step("execute", "超过最大工具轮次 max=%s", maxRounds);
		throw new IllegalStateException(AiChatPrompts.EXECUTION_MAX_TOOL_ROUNDS_EXCEEDED);
	}

	private AiChatPlanProposalHint parsePlanProposalHint(String toolResultJson) {
		if (!StringUtils.hasText(toolResultJson)) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(toolResultJson);
			if (!node.path("ok").asBoolean(false) || node.path("proposalId").asLong(0) <= 0) {
				return null;
			}
			return new AiChatPlanProposalHint(
					node.path("proposalId").asLong(),
					node.path("status").asText("PENDING"),
					node.path("goalTitle").asText(""),
					node.path("summary").asText(""),
					node.path("dayCount").asInt(0),
					textOrNull(node.path("startDate")),
					textOrNull(node.path("endDate")),
					node.path("dailyReminderTime").asText("08:00"));
		} catch (Exception e) {
			log.warn("解析计划草案工具结果失败: {}", e.getMessage());
			return null;
		}
	}

	private static String textOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asText().trim();
		return s.isEmpty() ? null : s;
	}
}
