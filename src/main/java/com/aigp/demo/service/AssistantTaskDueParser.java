package com.aigp.demo.service;

import com.aigp.demo.domain.chat.UserAssistantTask;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.util.StringUtils;

/** 解析助手任务截止日期/时刻，并同步 {@code due_date} 与 {@code due_at}。 */
public final class AssistantTaskDueParser {

	private static final DateTimeFormatter MINUTE_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter MINUTE_T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

	private AssistantTaskDueParser() {}

	/**
	 * @param dueDate 仅日期 yyyy-MM-dd；传空字符串表示清除（当 dueAt 未参与更新时）
	 * @param dueAt   日期+时刻，精确到分；传空字符串表示清除时刻（保留 dueDate 若已设）
	 * @param dueAtPresent 更新场景：JSON 是否包含 dueAt 字段
	 * @param dueDatePresent 更新场景：JSON 是否包含 dueDate 字段
	 */
	public static void applyDue(
			UserAssistantTask task,
			String dueDate,
			String dueAt,
			boolean dueDatePresent,
			boolean dueAtPresent) {
		if (dueAtPresent) {
			if (StringUtils.hasText(dueAt)) {
				LocalDateTime at = parseDueAt(dueAt);
				task.setDueAt(at);
				task.setDueDate(at.toLocalDate());
			} else {
				task.setDueAt(null);
			}
		}
		if (dueDatePresent) {
			if (StringUtils.hasText(dueDate)) {
				LocalDate date = LocalDate.parse(dueDate.trim());
				task.setDueDate(date);
				if (!dueAtPresent) {
					task.setDueAt(null);
				}
			} else if (!dueAtPresent || !StringUtils.hasText(dueAt)) {
				// 仅当本轮未同时写入 dueAt 时才允许清空 due_date
				task.setDueDate(null);
				if (!dueAtPresent) {
					task.setDueAt(null);
				}
			}
		}
		normalizeDue(task);
	}

	/** 保证有 due_at 时 due_date 同步为同日（兼容历史仅写时刻的落库）。 */
	public static void normalizeDue(UserAssistantTask task) {
		if (task.getDueAt() != null && task.getDueDate() == null) {
			task.setDueDate(task.getDueAt().toLocalDate());
		}
	}

	public static LocalDateTime parseDueAt(String raw) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		String s = raw.trim();
		try {
			if (s.contains("T")) {
				if (s.length() > 16) {
					s = s.substring(0, 16);
				}
				return truncateToMinute(LocalDateTime.parse(s, MINUTE_T));
			}
			if (s.length() > 16) {
				s = s.substring(0, 16);
			}
			return truncateToMinute(LocalDateTime.parse(s, MINUTE_SPACE));
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("dueAt 格式须为 yyyy-MM-dd HH:mm 或 yyyy-MM-dd'T'HH:mm");
		}
	}

	public static String formatDueAt(LocalDateTime dueAt) {
		return dueAt == null ? null : dueAt.format(MINUTE_SPACE);
	}

	public static String formatDueForDisplay(UserAssistantTask task) {
		if (task.getDueAt() != null) {
			return formatDueAt(task.getDueAt());
		}
		if (task.getDueDate() != null) {
			return task.getDueDate().toString();
		}
		return null;
	}

	private static LocalDateTime truncateToMinute(LocalDateTime dt) {
		return dt.withSecond(0).withNano(0);
	}
}
