package com.aigp.demo.service.chat;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** 从意图分析文本推断是否应启用任务工具（避免「无需调用 create_task」误匹配）。 */
public final class AiChatIntentSignals {

	private static final Pattern NEGATED_TOOL_USE = Pattern.compile(
			"(?i)(无需|不需要|不要|不应|未应)(调用|使用)?\\s*(任务工具|任务|待办)?|"
					+ "未提及任何任务|无具体任务|无任务需求|"
					+ "是否[^\\n]{0,24}(调用|使用)[^\\n]{0,16}工具[^\\n]{0,12}[：:][^\\n]*否");

	private static final Pattern POSITIVE_TOOL_USE = Pattern.compile(
			"(?i)(应|需要|必须|建议)(调用|使用)\\s*(任务工具|create_task|list_tasks|update_task|delete_task|get_task|propose_growth_plan)"
					+ "|应调用\\s*(create_task|list_tasks|update_task|delete_task|get_task|propose_growth_plan)"
					+ "|须调用\\s*(create_task|list_tasks|propose_growth_plan)"
					+ "|是否[^\\n]{0,24}(调用|使用)[^\\n]{0,16}工具[^\\n]{0,12}[：:][^\\n]*是");

	private AiChatIntentSignals() {}

	/**
	 * 意图分析结果是否表明本轮应启用助手任务工具。
	 */
	public static boolean suggestsTaskTools(String intentHint) {
		if (!StringUtils.hasText(intentHint)) {
			return false;
		}
		String h = intentHint.trim();
		if (NEGATED_TOOL_USE.matcher(h).find()) {
			return false;
		}
		if (POSITIVE_TOOL_USE.matcher(h).find()) {
			return true;
		}
		String lower = h.toLowerCase(Locale.ROOT);
		boolean namesTool = lower.contains("create_task")
				|| lower.contains("list_tasks")
				|| lower.contains("update_task")
				|| lower.contains("delete_task")
				|| lower.contains("get_task");
		return namesTool && (h.contains("应调用") || h.contains("需要调用") || h.contains("必须调用"));
	}

	/** 意图分析是否表明应调用 {@code propose_growth_plan} 生成待确认草案。 */
	public static boolean suggestsPlanProposalTools(String intentHint) {
		if (!StringUtils.hasText(intentHint)) {
			return false;
		}
		String lower = intentHint.trim().toLowerCase(Locale.ROOT);
		if (lower.contains("propose_growth_plan") || lower.contains("plan_proposal")) {
			return lower.contains("应调用")
					|| lower.contains("需要调用")
					|| lower.contains("必须调用")
					|| lower.contains("建议调用");
		}
		return (lower.contains("复习计划") || lower.contains("学习计划") || lower.contains("制定计划"))
				&& (lower.contains("propose") || lower.contains("草案") || lower.contains("待确认"));
	}
}
