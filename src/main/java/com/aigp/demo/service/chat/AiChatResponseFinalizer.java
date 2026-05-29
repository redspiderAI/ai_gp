package com.aigp.demo.service.chat;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 执行阶段助手正文清洗与兜底话术。 */
@Component
public class AiChatResponseFinalizer {

	private static final Pattern FAKE_TOOL_CALL =
			Pattern.compile("(?is)<\\s*tool_call\\b|</\\s*tool_call\\s*>|<\\s*tool\\s+name\\s*=");
	private static final Pattern FAKE_JSON_TOOL =
			Pattern.compile("(?is)\"action\"\\s*:\\s*\"list_tasks\"|\"action\"\\s*:\\s*\"create_task\"");
	private static final Pattern MOSTLY_ASCII =
			Pattern.compile("^[\\x00-\\x7F\\s<>/=\"'\\-_]+$");

	/**
	 * 将模型原始 content 转为可落库、可展示的用户正文。
	 */
	public String finalizeAssistantText(String content) {
		if (!StringUtils.hasText(content)) {
			return AiChatPrompts.FALLBACK_EMPTY_REPLY;
		}
		String text = content.trim();
		if (text.startsWith("[内部意图分析")) {
			int idx = text.indexOf('\n');
			return idx > 0 && idx < text.length() - 1 ? text.substring(idx + 1).trim() : text;
		}
		if (FAKE_TOOL_CALL.matcher(text).find() || FAKE_JSON_TOOL.matcher(text).find()) {
			return AiChatPrompts.FALLBACK_FAKE_TOOL_CALL;
		}
		if (MOSTLY_ASCII.matcher(text).matches() && text.length() < 200) {
			return AiChatPrompts.FALLBACK_GARBLED_REPLY;
		}
		return text;
	}
}
