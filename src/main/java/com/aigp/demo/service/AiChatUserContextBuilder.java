package com.aigp.demo.service;

import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.IdentityType;
import com.aigp.demo.repository.UserIdentityRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 为 AI 对话组装 system 上下文：按 {@link AiChatDataPlan} 能力切片，仅拼接本轮需要的规则与数据块。
 */
@Component
@RequiredArgsConstructor
public class AiChatUserContextBuilder {

	/** 纯闲聊 / 无工具场景的最小人设（不含任务工具说明）。 */
	private static final String SYSTEM_PERSONA =
			"""
			你是「AI成长计划」中的智能助手。请用简洁、友好的中文回复用户。
			请结合下方上下文回复；未提供的信息不要编造。
			日期格式：yyyy-MM-dd；时刻格式：yyyy-MM-dd HH:mm。
			""";

	/** 启用助手任务工具时追加：创建、提醒与日期时刻规则。 */
	private static final String TASK_SCHEDULING_RULES =
			"""

			你可以通过工具帮用户管理个人任务（创建、查询、更新、取消），任务与成长计划中的排期任务相互独立。

			【记待办 / 安排】
			- 用户说「帮我记录」「记一下」「安排」「开会」等时，应优先调用 create_task，并在回复中说明已创建的内容。
			- 未说明具体日期时，dueDate 使用下方【当前日期】，不要反复追问「哪一天」。
			- 用户说了具体时刻（如下午9点、21:00）时，用 create_task 的 dueAt，格式 yyyy-MM-dd HH:mm（精确到分）；仅「某天」无时刻时用 dueDate（yyyy-MM-dd）。
			- 用户在上文已问过日期、本轮只回答「今天」「明天」等时，结合对话历史理解并直接创建任务。

			【提醒 / 记得 / 别忘了】
			- 用户说「提醒我…」「记得…」「别忘了…」等且未给出具体几点时：必须调用 create_task 并填写 dueAt（yyyy-MM-dd HH:mm，精确到分）。
			- 日期部分：未说明哪一天时，默认用【当前日期】；若该日已无合理提醒时刻，可用次日。
			- 时刻部分：由你结合事项与【当前日期时间】推荐一个合理整点或半点（如喝水可约 1～2 小时后或下一整点），不要只填 dueDate 而无 dueAt。
			- 用户已明确时刻时，严格按用户语义填写 dueAt，不要擅自改点。
			- 创建成功后，向用户确认已设置提醒；**不要**声称「无法在指定时间主动推送」——后端会在到点自动投递：写入当前聊天会话、站内通知，并 WebSocket 推送（用户在线时聊天页可实时刷新）。
			- 用户问「到时候怎么提醒」时，说明：到点会在 App 聊天里收到助手消息，并有通知提醒；请保持 App 在线或允许系统通知。
			""";

	private static final String TASK_TOOL_RULES =
			"""

			【本轮须用任务工具】
			能创建就不要只追问；信息够用时立即 create_task，避免与上文重复确认。
			用户要查看/回顾计划或待办时，必须调用 list_tasks（使用 OpenAI 标准 tool_calls），禁止在正文里写 <tool_call> 等 XML。
			用户问「未来几天」「未来N天」时：list_tasks 的 dueFrom 填明天（yyyy-MM-dd），dueTo 按天数填截止日；不要把「今天」算进未来。
			""";

	private static final String COMPLETE_TASK_RULES =
			"""

			【标记任务已完成】
			- 用户说「XX完成了」「做完了」「搞定了」等：先 list_growth_tasks（date 默认今天）与 list_tasks（dueFrom/dueTo=【当前日期】，status=OPEN）查候选，**禁止未查就瞎猜 taskId**。
			- 用户说「今天」且未给具体日期时，date / dueFrom / dueTo 均用【当前日期】；用户明确说了「昨天」「5月20号」等则用对应日期。
			- 标题匹配：在候选里按用户描述模糊匹配 title；**唯一**匹配则立即 complete_growth_task 或 update_task(status=DONE)；**多条**匹配则列出并请用户确认是哪一条；**零条**则说明未找到，不要编造已完成。
			- 成长计划任务（tasks 表）：用 complete_growth_task；助手待办（user_assistant_tasks）：用 update_task 设 status=DONE。
			- App 端也可调用 POST /api/v1/users/me/tasks/complete（source=assistant|growth），与本规则一致。
			- 同一事项可能同时存在于两表（如「[学习计划] 英语」与 growth 任务「英语」）；优先 complete_growth_task，会自动同步助手待办；若仅助手待办则 update_task。
			- 用户未指明是哪项、且今日候选多于一条时，**必须追问**，不要默认猜第一个。
			""";

	private static final String GROWTH_TASK_TOOL_RULES =
			"""

			【成长计划每日任务（tasks 表）】
			用户查看或完成「学习计划里的今日任务」时，调用 list_growth_tasks / complete_growth_task；勿与 propose_growth_plan（未确认草案）混淆。
			""";

	private static final String PLAN_PROPOSAL_TOOL_RULES =
			"""

			【本轮须提交成长计划草案】
			- 用户要制定/复习/学习计划、备考方案（如六级一个月）时：必须调用 propose_growth_plan，填入完整 days 数组（每天一条，scheduledDate 连续或按周合理分布）。
			- 禁止在未确认前用 create_task 批量落库整份计划；确认由用户在 App 点击「确认计划」后由后端执行。
			- 工具成功后：用友好中文概括 goalTitle、天数、每日提醒时刻，并明确提示「请查看计划详情并确认后才会开始每日提醒」。
			- dailyReminderTime 默认 08:00；结合用户 weeklyHours 与偏好可调整为 07:00～21:00 的整点或半点。
			""";

	private final UserIdentityRepository userIdentityRepository;
	private final CompanionMemoryService companionMemoryService;

	/**
	 * 按本轮 {@link AiChatDataPlan} 拼接 system：纯闲聊仅人设+时间；有工具时再追加对应规则块与摘要。
	 */
	public String buildSystemPrompt(
			AppUser user,
			AiChatDataPlan plan,
			String tasksSummary,
			String growthTasksSummary,
			String intentHint,
			String unsupportedHint,
			List<Long> messageImageAssetIds) {
		StringBuilder sb = new StringBuilder(SYSTEM_PERSONA);
		appendCurrentDate(sb, user);
		if (messageImageAssetIds != null && !messageImageAssetIds.isEmpty()) {
			String ids = messageImageAssetIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
			sb.append("\n【本轮用户上传图片 assetId】")
					.append(ids)
					.append("\n默认不要把图片记入任务；仅当用户明确说「把图/照片记进待办」时，create_task 传 imageAssetIds。\n");
		}
		if (plan != null && StringUtils.hasText(plan.reason())) {
			sb.append("\n【本轮数据规划说明】").append(plan.reason().trim()).append('\n');
		}
		if (StringUtils.hasText(intentHint)) {
			sb.append("\n【内部意图参考（勿原样复述给用户）】\n").append(intentHint.trim()).append('\n');
		}
		if (StringUtils.hasText(unsupportedHint)) {
			sb.append("\n【暂未开放能力（须在回复中说明）】\n").append(unsupportedHint.trim()).append('\n');
		}
		if (plan != null && plan.needTaskTools()) {
			sb.append(TASK_SCHEDULING_RULES);
			sb.append(TASK_TOOL_RULES);
			sb.append(COMPLETE_TASK_RULES);
		}
		if (plan != null && plan.needGrowthPlanTools()) {
			sb.append(GROWTH_TASK_TOOL_RULES);
			if (!plan.needTaskTools()) {
				sb.append(COMPLETE_TASK_RULES);
			}
		}
		if (plan != null && plan.needPlanProposalTools()) {
			sb.append(PLAN_PROPOSAL_TOOL_RULES);
		}
		if (plan == null || plan.needUserProfile()) {
			sb.append("\n【用户基本信息】\n");
			appendLine(sb, "昵称", user.getNickname());
			appendLine(sb, "对外ID", user.getUid());
			if (user.getProfileAge() != null) {
				appendLine(sb, "年龄", String.valueOf(user.getProfileAge()));
			}
			appendLine(sb, "职业", user.getProfileOccupation());
			appendLine(sb, "爱好", user.getProfileHobbies());
			appendLine(sb, "希望探索的方向", user.getProfileExploration());
			if (user.getWeeklyHours() != null) {
				appendLine(sb, "每周可投入小时数", String.valueOf(user.getWeeklyHours()));
			}
			appendLine(sb, "时区", user.getTimezone());
			appendLine(sb, "语言", user.getLanguage());
			appendLine(sb, "已完成首次画像", Boolean.TRUE.equals(user.getOnboardingCompleted()) ? "是" : "否");
			userIdentityRepository
					.findByUser_IdAndIdentityType(user.getId(), IdentityType.email)
					.ifPresent(id -> appendLine(sb, "登录邮箱", id.getIdentifier()));
			userIdentityRepository
					.findByUser_IdAndIdentityType(user.getId(), IdentityType.phone)
					.ifPresent(id -> appendLine(sb, "手机号", maskPhone(id.getIdentifier())));
		}
		if (shouldIncludeCompanionMemory(plan)) {
			companionMemoryService.getMemoryTextForChat(user.getId()).ifPresent(memory -> {
				sb.append("\n【长期陪伴记忆（由系统每周整理，勿编造；与用户本轮说法冲突时以本轮为准）】\n");
				sb.append(memory.trim()).append('\n');
			});
		}
		if (plan == null || plan.needTaskList()) {
			sb.append("\n【助手任务摘要（详细请用 list_tasks 查询）】\n");
			if (StringUtils.hasText(tasksSummary)) {
				sb.append(tasksSummary.trim());
			} else {
				sb.append("（当前筛选条件下暂无任务）");
			}
		}
		if (plan == null || plan.needGrowthTaskList()) {
			sb.append("\n【今日成长计划任务摘要（详细请用 list_growth_tasks 查询）】\n");
			if (StringUtils.hasText(growthTasksSummary)) {
				sb.append(growthTasksSummary.trim());
			} else {
				LocalDate today = LocalDate.now(resolveZone(user.getTimezone()));
				sb.append("（").append(today).append(" 暂无成长计划任务；未确认计划则无数据）");
			}
		}
		return sb.toString();
	}

	/**
	 * 纯闲聊不注入长期记忆，减少 execute 阶段 token；任务/计划/画像场景仍注入。
	 */
	private static boolean shouldIncludeCompanionMemory(AiChatDataPlan plan) {
		if (plan == null) {
			return true;
		}
		return plan.needUserProfile()
				|| plan.needTaskTools()
				|| plan.needGrowthPlanTools()
				|| plan.needPlanProposalTools()
				|| plan.needTaskList()
				|| plan.needGrowthTaskList();
	}

	private static final DateTimeFormatter CURRENT_DATETIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static void appendCurrentDate(StringBuilder sb, AppUser user) {
		ZoneId zone = resolveZone(user.getTimezone());
		LocalDateTime now = LocalDateTime.now(zone);
		sb.append("\n【当前日期时间】")
				.append(now.format(CURRENT_DATETIME))
				.append("（时区 ")
				.append(zone.getId())
				.append("）\n");
	}

	private static ZoneId resolveZone(String timezone) {
		if (!StringUtils.hasText(timezone)) {
			return ZoneId.of("Asia/Shanghai");
		}
		try {
			return ZoneId.of(timezone.trim());
		} catch (Exception e) {
			return ZoneId.of("Asia/Shanghai");
		}
	}

	private static void appendLine(StringBuilder sb, String label, String value) {
		if (StringUtils.hasText(value)) {
			sb.append("- ").append(label).append("：").append(value.trim()).append('\n');
		}
	}

	private static String maskPhone(String phone) {
		if (phone == null || phone.length() < 11) {
			return phone;
		}
		return phone.substring(0, 3) + "****" + phone.substring(7);
	}
}
