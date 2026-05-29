package com.aigp.demo.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 解析规划 JSON、校验能力 ID，并做少量确定性补全（如短句续聊）。 */
@Component
public class AiChatRouteResolver {

	private static final Pattern JSON_BLOCK =
			Pattern.compile("\\{[^{}]*\"capabilities\"[^{}]*\\}", Pattern.DOTALL);

	private final ObjectMapper objectMapper;

	public AiChatRouteResolver(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public AiChatRoutePlan parse(String raw) {
		if (!StringUtils.hasText(raw)) {
			return AiChatRoutePlan.defaults();
		}
		String json = extractJson(raw.trim());
		try {
			JsonNode node = objectMapper.readTree(json);
			if (node.has("capabilities")) {
				return parseCapabilityPlan(node);
			}
			return parseLegacyPlan(node);
		} catch (Exception e) {
			return AiChatRoutePlan.defaults();
		}
	}

	public AiChatRoutePlan refine(
			AiChatRoutePlan plan,
			String userMessage,
			String historySnippet,
			boolean sessionHasMessages) {
		if (plan == null) {
			plan = AiChatRoutePlan.defaults();
		}
		Set<AiChatCapabilityId> caps = new LinkedHashSet<>(plan.capabilities());
		List<AiChatCapabilityId> unsupported = new ArrayList<>(plan.unsupported());
		String msg = userMessage == null ? "" : userMessage.trim();

		if (sessionHasMessages || StringUtils.hasText(historySnippet)) {
			caps.add(AiChatCapabilityId.CHAT_HISTORY);
		}
		if (looksLikeDateFollowUp(msg, historySnippet)) {
			caps.add(AiChatCapabilityId.CHAT_HISTORY);
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
		}
		if (looksLikePlanProposalRequest(msg, historySnippet)) {
			caps.add(AiChatCapabilityId.PLAN_PROPOSAL);
			caps.add(AiChatCapabilityId.USER_PROFILE);
			caps.add(AiChatCapabilityId.CHAT_HISTORY);
		}
		if (looksLikeTaskCompleteRequest(msg)) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
			caps.add(AiChatCapabilityId.GROWTH_PLAN_TASKS);
			caps.add(AiChatCapabilityId.CHAT_HISTORY);
		}
		if (AiChatProfilePhraseSignals.looksLikeProfileQuery(msg)) {
			caps.add(AiChatCapabilityId.USER_PROFILE);
		}

		caps.removeIf(c -> !c.available());
		for (AiChatCapabilityId u : List.copyOf(unsupported)) {
			if (u.available()) {
				unsupported.remove(u);
			}
		}

		if (caps.isEmpty()) {
			caps.add(AiChatCapabilityId.CHAT);
		}

		return new AiChatRoutePlan(caps, unsupported, plan.taskListStatus(), plan.reason());
	}

	private AiChatRoutePlan parseCapabilityPlan(JsonNode node) {
		List<String> capIds = new ArrayList<>();
		if (node.path("capabilities").isArray()) {
			node.path("capabilities").forEach(n -> capIds.add(n.asText()));
		}
		List<String> unIds = new ArrayList<>();
		if (node.path("unsupported").isArray()) {
			node.path("unsupported").forEach(n -> unIds.add(n.asText()));
		}

		Set<AiChatCapabilityId> capabilities = new LinkedHashSet<>();
		for (AiChatCapabilityId cap : AiChatCapabilityCatalog.parseIdList(capIds)) {
			if (cap.available()) {
				capabilities.add(cap);
			}
		}

		List<AiChatCapabilityId> unsupported = new ArrayList<>();
		for (AiChatCapabilityId cap : AiChatCapabilityCatalog.parseIdList(unIds)) {
			if (!cap.available()) {
				unsupported.add(cap);
			}
		}

		if (capabilities.isEmpty() && unsupported.isEmpty()) {
			capabilities.add(AiChatCapabilityId.CHAT);
		}

		String status = normalizeTaskListStatus(node.path("taskListStatus"));
		String reason = resolveRouteReason(node);
		return new AiChatRoutePlan(capabilities, unsupported, status, reason);
	}

	private AiChatRoutePlan parseLegacyPlan(JsonNode node) {
		Set<AiChatCapabilityId> caps = new LinkedHashSet<>();
		caps.add(AiChatCapabilityId.CHAT);
		if (node.path("needChatHistory").asBoolean(true)) {
			caps.add(AiChatCapabilityId.CHAT_HISTORY);
		}
		if (node.path("needUserProfile").asBoolean(true)) {
			caps.add(AiChatCapabilityId.USER_PROFILE);
		}
		if (node.path("needTaskList").asBoolean(true) || node.path("needTaskTools").asBoolean(true)) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
		}
		String status = normalizeTaskListStatus(node.path("taskListStatus"));
		if (status == null && (node.path("needTaskList").asBoolean(true) || node.path("needTaskTools").asBoolean(true))) {
			status = "OPEN";
		}
		return new AiChatRoutePlan(caps, List.of(), status, resolveRouteReason(node));
	}

	private static String extractJson(String json) {
		Matcher m = JSON_BLOCK.matcher(json);
		if (m.find()) {
			return m.group();
		}
		int start = json.indexOf('{');
		int end = json.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return json.substring(start, end + 1);
		}
		return json;
	}

	private static String resolveRouteReason(JsonNode node) {
		String codeRaw = textOrNull(node.path("routeReasonCode"));
		var code = AiChatRouteReasonCode.fromCode(codeRaw);
		if (code.isPresent()) {
			return "规划路由：" + code.get().label();
		}
		String reason = node.path("reason").asText("");
		return StringUtils.hasText(reason) ? reason.trim() : "规划路由：未识别";
	}

	/** taskListStatus 白名单：OPEN / DONE / CANCELLED，非法则 null。 */
	private static String normalizeTaskListStatus(JsonNode node) {
		String status = textOrNull(node);
		if (status == null) {
			return null;
		}
		String upper = status.toUpperCase(Locale.ROOT);
		if ("OPEN".equals(upper) || "DONE".equals(upper) || "CANCELLED".equals(upper)) {
			return upper;
		}
		return null;
	}

	private static String textOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asText();
		return s.isBlank() ? null : s.trim();
	}

	private static boolean looksLikePlanProposalRequest(String msg, String historySnippet) {
		if (containsPlanKeyword(msg)) {
			return true;
		}
		return containsPlanKeyword(historySnippet)
				&& StringUtils.hasText(msg)
				&& (msg.contains("确认") || msg.contains("同意") || msg.contains("修改") || msg.length() <= 24);
	}

	private static boolean containsPlanKeyword(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		return text.contains("复习计划")
				|| text.contains("学习计划")
				|| text.contains("制定计划")
				|| text.contains("备考")
				|| text.contains("复习方案")
				|| (text.contains("计划") && (text.contains("考试") || text.contains("六级") || text.contains("四级")));
	}

	private static boolean looksLikeDateFollowUp(String msg, String historySnippet) {
		if (!StringUtils.hasText(msg) || msg.length() > 12) {
			return false;
		}
		if (!StringUtils.hasText(historySnippet)) {
			return false;
		}
		String h = historySnippet.toLowerCase(Locale.ROOT);
		boolean snippetAboutTask =
				h.contains("记录") || h.contains("待办") || h.contains("开会") || h.contains("安排");
		if (!snippetAboutTask) {
			return false;
		}
		return msg.equals("今天")
				|| msg.equals("明天")
				|| msg.equals("后天")
				|| msg.matches(".*\\d{4}-\\d{2}-\\d{2}.*")
				|| msg.matches(".*\\d{1,2}月\\d{1,2}.*");
	}

	private static boolean looksLikeTaskCompleteRequest(String msg) {
		return AiChatTaskPhraseSignals.isTaskCompleteStatement(msg);
	}
}
