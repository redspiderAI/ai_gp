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
			"(?is)\\s*(应|需要|必须|建议)(调用|使用)\\s*(任务工具|create_task|list_tasks|update_task|delete_task|get_task|list_growth_tasks|complete_growth_task|propose_growth_plan)"
					+ "|\\s*应调用\\s*(create_task|list_tasks|update_task|delete_task|get_task|list_growth_tasks|complete_growth_task|propose_growth_plan)"
					+ "|\\s*须调用\\s*(create_task|list_tasks|propose_growth_plan|complete_growth_task)"
					+ "|\\s*(?:可)?同步调用\\s*(list_tasks|list_growth_tasks)"
					+ "|是否[^\\n]{0,24}(调用|使用)[^\\n]{0,16}工具[^\\n]{0,12}[：:][^\\n]*是");

	private static final Pattern NEGATED_PROPOSE = Pattern.compile(
			"(?is)\\s*(无需|不需要|不要|不应)(调用|使用)?[^\\n]{0,32}propose_growth_plan");

	private static final Pattern POSITIVE_PROPOSE = Pattern.compile(
			"(?is)\\s*(应|需要|必须|建议)(调用|使用)\\s*propose_growth_plan|\\s*须调用\\s*propose_growth_plan");

	private static final Pattern POSITIVE_LIST_GROWTH = Pattern.compile(
			"(?is)\\s*(应|需要|必须|建议)(调用|使用)\\s*list_growth_tasks(?:\\s|，|,|$)"
					+ "|\\s*(?:可)?同步调用\\s*list_growth_tasks(?:\\s|，|,|$)"
					+ "|\\s*须调用\\s*list_growth_tasks(?:\\s|，|,|$)");

	private static final Pattern POSITIVE_COMPLETE_GROWTH = Pattern.compile(
			"(?is)\\s*(应|需要|必须|建议)(调用|使用)\\s*complete_growth_task|\\s*须调用\\s*complete_growth_task");

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
		String h = intentHint.trim();
		if (NEGATED_PROPOSE.matcher(h).find()) {
			return false;
		}
		if (POSITIVE_PROPOSE.matcher(h).find()) {
			return true;
		}
		String lower = h.toLowerCase(Locale.ROOT);
		return (lower.contains("复习计划") || lower.contains("学习计划") || lower.contains("制定计划"))
				&& (lower.contains("propose") || lower.contains("草案") || lower.contains("待确认"));
	}

	/** 意图分析是否表明应查询/完成成长计划 tasks 表任务。 */
	public static boolean suggestsGrowthPlanTools(String intentHint) {
		if (!StringUtils.hasText(intentHint)) {
			return false;
		}
		String h = intentHint.trim();
		if (isNegatedToolLine(h, "list_growth_tasks") || isNegatedToolLine(h, "complete_growth_task")) {
			return false;
		}
		if (POSITIVE_LIST_GROWTH.matcher(h).find()
				|| POSITIVE_COMPLETE_GROWTH.matcher(h).find()
				|| (h.contains("list_growth_tasks")
						&& (h.contains("同步调用 list_growth") || h.contains("应调用 list_growth")))) {
			return true;
		}
		String lower = h.toLowerCase(Locale.ROOT);
		return (lower.contains("完成了") || lower.contains("做完了") || lower.contains("标记完成"))
				&& (lower.contains("学习") || lower.contains("计划") || lower.contains("成长") || lower.contains("任务"));
	}

	private static boolean isNegatedToolLine(String hint, String toolName) {
		String key = toolName.toLowerCase(Locale.ROOT);
		for (String line : hint.split("\\R")) {
			String lower = line.toLowerCase(Locale.ROOT);
			int idx = lower.indexOf(key);
			if (idx < 0) {
				continue;
			}
			String before = lower.substring(0, idx);
			if (before.contains("无需") || before.contains("不要") || before.contains("不需要")) {
				return true;
			}
		}
		return false;
	}
}
