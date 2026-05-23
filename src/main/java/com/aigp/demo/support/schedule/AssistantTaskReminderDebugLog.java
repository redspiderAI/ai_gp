package com.aigp.demo.support.schedule;

import com.aigp.demo.config.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 助手任务到点提醒调试日志：追加写入 txt，便于本地/测试环境排查 cron、调度锁、跳过原因与投递结果。
 * <p>
 * 开关：{@code app.task-reminder.debug-log-enabled}；路径：{@code app.task-reminder.debug-log-file}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantTaskReminderDebugLog {

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	private static final Object FILE_LOCK = new Object();

	private final AppProperties appProperties;

	public boolean isEnabled() {
		return appProperties.getTaskReminder().isDebugLogEnabled();
	}

	public void line(String phase, String detail, Object... args) {
		if (!isEnabled()) {
			return;
		}
		String text = formatDetail(detail, args);
		log.info("[TASK-REMINDER-DBG] [{}] {}", phase, text);
		writeFile("[" + now() + "] [" + phase + "] " + text + "\n");
	}

	public void tickBegin(String source, int candidateCount, String scanWindow) {
		line("TICK", "source=%s candidates=%d scan=%s", source, candidateCount, scanWindow);
	}

	public void tickEnd(String source, int sent, int candidates) {
		line("TICK", "source=%s end sent=%d candidates=%d", source, sent, candidates);
	}

	public void skip(String source, Long taskId, Long userId, String reason) {
		line("SKIP", "source=%s taskId=%s userId=%s reason=%s", source, taskId, userId, reason);
	}

	public void sent(
			String source,
			Long taskId,
			Long userId,
			Long sessionId,
			Long messageId,
			String dueDisplay,
			String reminderSentAt) {
		line(
				"SENT",
				"source=%s taskId=%s userId=%s sessionId=%s messageId=%s due=%s reminderSentAt=%s",
				source,
				taskId,
				userId,
				sessionId,
				messageId,
				dueDisplay,
				reminderSentAt);
	}

	public void schedule(String action, Long taskId, String fireAt, String detail) {
		line("SCHEDULE", "action=%s taskId=%s fireAt=%s %s", action, taskId, fireAt, detail == null ? "" : detail);
	}

	private void writeFile(String text) {
		if (!StringUtils.hasText(text)) {
			return;
		}
		String pathStr = appProperties.getTaskReminder().getDebugLogFile();
		if (!StringUtils.hasText(pathStr)) {
			return;
		}
		try {
			Path path = resolvePath(pathStr.trim());
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			synchronized (FILE_LOCK) {
				Files.writeString(
						path,
						text,
						StandardCharsets.UTF_8,
						StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			}
		} catch (IOException e) {
			log.warn("[TASK-REMINDER-DBG] 写入调试文件失败 path={}: {}", pathStr, e.getMessage());
		}
	}

	private static Path resolvePath(String pathStr) {
		Path p = Paths.get(pathStr);
		if (p.isAbsolute()) {
			return p;
		}
		return Paths.get(System.getProperty("user.dir", ".")).resolve(p).normalize();
	}

	private static String now() {
		return LocalDateTime.now().format(TS);
	}

	private static String formatDetail(String detail, Object[] args) {
		if (detail == null) {
			return "";
		}
		if (args != null && args.length > 0) {
			try {
				return String.format(detail, args);
			} catch (Exception e) {
				return detail;
			}
		}
		return detail;
	}
}
