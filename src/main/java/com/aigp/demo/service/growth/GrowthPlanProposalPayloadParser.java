package com.aigp.demo.service.growth;

import com.aigp.demo.domain.enums.GrowthDomain;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.util.StringUtils;

/** 校验并解析成长计划草案 JSON，拒绝非法或超长输入。 */
public final class GrowthPlanProposalPayloadParser {

	public static final int MAX_DAYS = 31;
	public static final int MIN_ESTIMATED_MINUTES = 15;
	public static final int MAX_ESTIMATED_MINUTES = 480;
	public static final int MAX_GOAL_TITLE = 200;
	public static final int MAX_SUMMARY = 2000;
	public static final int MAX_DAY_TITLE = 200;
	public static final int MAX_DAY_DESCRIPTION = 1000;

	private GrowthPlanProposalPayloadParser() {}

	public static GrowthPlanProposalPayload parse(JsonNode root) {
		if (root == null || root.isMissingNode()) {
			throw new IllegalArgumentException("计划内容不能为空");
		}
		int version = root.path("version").asInt(1);
		if (version != 1) {
			throw new IllegalArgumentException("不支持的计划版本: " + version);
		}
		String goalTitle = requireText(root.path("goalTitle"), "goalTitle", MAX_GOAL_TITLE);
		String goalDescription = optionalText(root.path("goalDescription"), 2000);
		GrowthDomain domain = parseDomain(root.path("domain"));
		LocalDate deadline = parseDateOrNull(root.path("deadline"), "deadline");
		String summary = requireText(root.path("summary"), "summary", MAX_SUMMARY);
		LocalTime reminderTime = parseReminderTime(root.path("dailyReminderTime"));

		JsonNode daysNode = root.path("days");
		if (!daysNode.isArray() || daysNode.isEmpty()) {
			throw new IllegalArgumentException("days 须为非空数组，最多 " + MAX_DAYS + " 天");
		}
		if (daysNode.size() > MAX_DAYS) {
			throw new IllegalArgumentException("计划天数不能超过 " + MAX_DAYS + " 天");
		}
		List<GrowthPlanProposalDayItem> days = new ArrayList<>();
		for (JsonNode day : daysNode) {
			int dayIndex = day.path("dayIndex").asInt(0);
			if (dayIndex < 1) {
				throw new IllegalArgumentException("dayIndex 须 >= 1");
			}
			LocalDate scheduledDate = parseDate(day.path("scheduledDate"), "scheduledDate");
			String title = requireText(day.path("title"), "days.title", MAX_DAY_TITLE);
			String description = optionalText(day.path("description"), MAX_DAY_DESCRIPTION);
			int estimated = day.path("estimatedMinutes").asInt(0);
			if (estimated < MIN_ESTIMATED_MINUTES || estimated > MAX_ESTIMATED_MINUTES) {
				throw new IllegalArgumentException(
						"estimatedMinutes 须在 " + MIN_ESTIMATED_MINUTES + "～" + MAX_ESTIMATED_MINUTES);
			}
			days.add(new GrowthPlanProposalDayItem(dayIndex, scheduledDate, title, description, estimated));
		}
		days.sort(Comparator.comparingInt(GrowthPlanProposalDayItem::dayIndex));
		LocalDate first = days.get(0).scheduledDate();
		LocalDate last = days.get(days.size() - 1).scheduledDate();
		if (deadline != null && deadline.isBefore(last)) {
			throw new IllegalArgumentException("deadline 不能早于最后一天的 scheduledDate");
		}
		if (deadline == null) {
			deadline = last;
		}
		for (int i = 1; i < days.size(); i++) {
			if (days.get(i).scheduledDate().isBefore(days.get(i - 1).scheduledDate())) {
				throw new IllegalArgumentException("days 的 scheduledDate 须按 dayIndex 非递减");
			}
		}
		if (first.isAfter(last)) {
			throw new IllegalArgumentException("计划日期范围无效");
		}
		return new GrowthPlanProposalPayload(
				version, goalTitle, goalDescription, domain, deadline, summary, reminderTime, List.copyOf(days));
	}

	private static GrowthDomain parseDomain(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(node.asText())) {
			return GrowthDomain.LANGUAGE;
		}
		try {
			return GrowthDomain.valueOf(node.asText().trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"domain 须为 SKILLS/CERTIFICATION/LANGUAGE/SOFT_SKILLS/SIDE_HUSTLE 之一");
		}
	}

	private static LocalTime parseReminderTime(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(node.asText())) {
			return LocalTime.of(8, 0);
		}
		try {
			return LocalTime.parse(node.asText().trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("dailyReminderTime 须为 HH:mm");
		}
	}

	private static LocalDate parseDate(JsonNode node, String field) {
		String raw = textOrNull(node);
		if (raw == null) {
			throw new IllegalArgumentException(field + " 必填，格式 yyyy-MM-dd");
		}
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(field + " 格式须为 yyyy-MM-dd");
		}
	}

	private static LocalDate parseDateOrNull(JsonNode node, String field) {
		String raw = textOrNull(node);
		if (raw == null) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(field + " 格式须为 yyyy-MM-dd");
		}
	}

	private static String requireText(JsonNode node, String field, int maxLen) {
		String s = textOrNull(node);
		if (s == null) {
			throw new IllegalArgumentException(field + " 不能为空");
		}
		if (s.length() > maxLen) {
			throw new IllegalArgumentException(field + " 长度不能超过 " + maxLen);
		}
		return s;
	}

	private static String optionalText(JsonNode node, int maxLen) {
		String s = textOrNull(node);
		if (s == null) {
			return null;
		}
		if (s.length() > maxLen) {
			throw new IllegalArgumentException("字段长度不能超过 " + maxLen);
		}
		return s;
	}

	private static String textOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asText().trim();
		return s.isEmpty() ? null : s;
	}
}
