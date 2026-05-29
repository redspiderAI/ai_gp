package com.aigp.demo.service.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/** 能力注册表：生成规划 prompt、校验 ID、生成「暂未开放」话术。 */
public final class AiChatCapabilityCatalog {

	private AiChatCapabilityCatalog() {}

	/**
	 * 路由规划 LLM 的 system 动态附录：已上线/未上线能力 id 列表（JSON 规则见 {@link AiChatPrompts#ROUTE_PLAN_JSON_RULES}）。
	 */
	public static String plannerCapabilityListAppendix() {
		StringBuilder sb = new StringBuilder();
		sb.append("【已上线能力】只能从下列 id 的 capabilities 中选择（可多选）：\n");
		for (AiChatCapabilityId cap : AiChatCapabilityId.values()) {
			if (cap.available()) {
				sb.append("- ")
						.append(cap.id())
						.append("：")
						.append(cap.label())
						.append('\n');
			}
		}
		sb.append("\n【未上线能力】用户若明确需要，写入 unsupported 数组（id 如下），不要放入 capabilities：\n");
		for (AiChatCapabilityId cap : AiChatCapabilityId.values()) {
			if (!cap.available()) {
				sb.append("- ").append(cap.id()).append("：").append(cap.label()).append('\n');
			}
		}
		return sb.toString();
	}

	public static String buildUnsupportedOnlyReply(List<AiChatCapabilityId> unsupported) {
		String names =
				unsupported.stream().map(AiChatCapabilityId::label).collect(Collectors.joining("、"));
		return AiChatPrompts.UNSUPPORTED_ONLY_REPLY_TEMPLATE.formatted(names).trim();
	}

	public static String buildUnsupportedHintForExecute(List<AiChatCapabilityId> unsupported) {
		if (unsupported == null || unsupported.isEmpty()) {
			return null;
		}
		String names =
				unsupported.stream().map(AiChatCapabilityId::label).collect(Collectors.joining("、"));
		return AiChatPrompts.unsupportedHintForExecute(names);
	}

	public static List<AiChatCapabilityId> parseIdList(Iterable<String> ids) {
		List<AiChatCapabilityId> out = new ArrayList<>();
		if (ids == null) {
			return out;
		}
		for (String raw : ids) {
			fromId(raw).ifPresent(out::add);
		}
		return out;
	}

	public static java.util.Optional<AiChatCapabilityId> fromId(String raw) {
		return AiChatCapabilityId.fromId(raw);
	}

	public static List<String> toIdStrings(List<AiChatCapabilityId> caps) {
		return caps.stream().map(AiChatCapabilityId::id).toList();
	}

	public static boolean isBlankReason(String reason) {
		return !StringUtils.hasText(reason);
	}
}
