package com.aigp.demo.service.chat;

import com.aigp.demo.service.chat.AiChatStructuredIntent.AssistantTaskOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.GrowthTaskOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.PlanOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.PrimaryGoal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 解析意图分析 LLM 输出的 JSON，校验枚举白名单。 */
@Component
public class AiChatIntentResolver {

	private static final Pattern JSON_BLOCK =
			Pattern.compile("\\{[^{}]*\"primaryGoal\"[^{}]*\\}", Pattern.DOTALL);

	private final ObjectMapper objectMapper;

	public AiChatIntentResolver(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * @param raw 模型输出
	 * @return 解析成功且字段合法时返回；否则 empty（调用方跳过意图增强）
	 */
	public Optional<AiChatStructuredIntent> parse(String raw) {
		if (!StringUtils.hasText(raw)) {
			return Optional.empty();
		}
		String json = extractJson(raw.trim());
		try {
			JsonNode node = objectMapper.readTree(json);
			Optional<PrimaryGoal> primary = enumOf(PrimaryGoal.class, node.path("primaryGoal").asText(null));
			Optional<AssistantTaskOp> assistant =
					enumOf(AssistantTaskOp.class, node.path("assistantTaskOp").asText("NONE"));
			Optional<GrowthTaskOp> growth = enumOf(GrowthTaskOp.class, node.path("growthTaskOp").asText("NONE"));
			Optional<PlanOp> plan = enumOf(PlanOp.class, node.path("planOp").asText("NONE"));
			if (primary.isEmpty() || assistant.isEmpty() || growth.isEmpty() || plan.isEmpty()) {
				return Optional.empty();
			}
			boolean reminderNeedsDueAt = node.path("reminderNeedsDueAt").asBoolean(false);
			boolean completeNeedsDisambiguation = node.path("completeNeedsDisambiguation").asBoolean(false);
			return Optional.of(new AiChatStructuredIntent(
					primary.get(),
					assistant.get(),
					growth.get(),
					plan.get(),
					reminderNeedsDueAt,
					completeNeedsDisambiguation));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private static <E extends Enum<E>> Optional<E> enumOf(Class<E> type, String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String key = raw.trim().toUpperCase(Locale.ROOT);
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(key)) {
				return Optional.of(constant);
			}
		}
		return Optional.empty();
	}

	private static String extractJson(String text) {
		Matcher m = JSON_BLOCK.matcher(text);
		if (m.find()) {
			return m.group();
		}
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return text.substring(start, end + 1);
		}
		return text;
	}
}
