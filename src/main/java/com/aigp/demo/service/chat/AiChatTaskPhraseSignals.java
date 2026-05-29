package com.aigp.demo.service.chat;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 区分「任务是否已完成」的<strong>询问</strong>与「标记/陈述已完成」的<strong>指令</strong>，
 * 避免「完成了吗」被误判为完成操作。
 */
public final class AiChatTaskPhraseSignals {

	private static final Pattern TASK_STATUS_QUERY = Pattern.compile(
			".*(完成了吗|做完了吗|搞定了吗|已完成吗|有没有完成|是否完成|完成了没有|完成了没|有没有做完|是否做完|"
					+ "忘记.*完成|完成了吗|做完吗|完成了么|做完了么).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_COMPLETE_STATEMENT = Pattern.compile(
			".*(完成了|做完了|搞定了|已完成|标记完成|任务完成|两个都完成|都完成了|全部完成).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_DELETE = Pattern.compile(
			".*(取消|删除|删掉).*(任务|待办|提醒).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_CREATE = Pattern.compile(
			".*(提醒我|记得|别忘了|记一下|帮我记|帮我记录|记个|安排一下|记待办|帮我安排|记录一下).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_LIST_QUERY = Pattern.compile(
			".*(未来|接下来|这几天|最近|哪些|列出|查看|查询|看看|有什么|有啥|帮我看|查一下|查下)"
					+ ".*(任务|待办|提醒|安排|计划).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_LIST_SHORT = Pattern.compile(
			"^(我)?(的)?(任务|待办|提醒)(列表|清单)?$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private AiChatTaskPhraseSignals() {}

	/** 用户是在问任务状态（如「完成了吗」「有没有完成」），不是要标记完成。 */
	public static boolean isTaskStatusQuery(String msg) {
		if (!StringUtils.hasText(msg)) {
			return false;
		}
		String t = msg.trim();
		if (TASK_STATUS_QUERY.matcher(t).matches()) {
			return true;
		}
		// 「…完成…吗/没」且含任务/会议/提醒等语境
		if ((t.contains("吗") || t.contains("?") || t.contains("？"))
				&& (t.contains("完成") || t.contains("做完") || t.contains("搞定"))
				&& (t.contains("任务") || t.contains("待办") || t.contains("提醒") || t.contains("会议") || t.contains("计划"))) {
			return true;
		}
		return t.contains("忘记了") && (t.contains("完成") || t.contains("做完"));
	}

	/** 用户陈述或要求标记任务已完成（排除纯询问）。 */
	public static boolean isTaskCompleteStatement(String msg) {
		if (!StringUtils.hasText(msg) || isTaskStatusQuery(msg)) {
			return false;
		}
		return TASK_COMPLETE_STATEMENT.matcher(msg.trim()).matches();
	}

	/** 是否含完成类关键词（含询问，供 intent 等宽松场景二次过滤）。 */
	public static boolean mentionsCompletion(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return lower.contains("完成了") || lower.contains("做完了") || lower.contains("标记完成");
	}

	/** 用户本轮是否在请求创建/完成/取消类任务变更（不含纯状态询问）。 */
	public static boolean looksLikeTaskMutationRequest(String msg) {
		if (!StringUtils.hasText(msg) || isTaskStatusQuery(msg)) {
			return false;
		}
		String t = msg.trim();
		return TASK_CREATE.matcher(t).matches()
				|| isTaskCompleteStatement(t)
				|| TASK_DELETE.matcher(t).matches();
	}

	/** 用户是否在请求查看/列出待办（供查询类兜底，与确定性路由语义对齐）。 */
	public static boolean looksLikeTaskListRequest(String msg) {
		if (!StringUtils.hasText(msg) || isTaskStatusQuery(msg)) {
			return false;
		}
		String t = msg.trim();
		if (TASK_LIST_SHORT.matcher(t).matches()) {
			return true;
		}
		if (TASK_LIST_QUERY.matcher(t).matches()) {
			return true;
		}
		String lower = t.toLowerCase(Locale.ROOT);
		return (lower.contains("待办") || lower.contains("任务") || lower.contains("提醒"))
				&& (lower.contains("查") || lower.contains("列") || lower.contains("有哪些") || lower.contains("看看"));
	}
}
