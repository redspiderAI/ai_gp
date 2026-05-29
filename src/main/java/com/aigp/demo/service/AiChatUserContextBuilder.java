package com.aigp.demo.service;

import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.IdentityType;
import com.aigp.demo.repository.UserIdentityRepository;
import com.aigp.demo.service.chat.AiChatPrompts;
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
 * 规则文案见 {@link AiChatPrompts} 执行阶段常量。
 */
@Component
@RequiredArgsConstructor
public class AiChatUserContextBuilder {

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
		StringBuilder sb = new StringBuilder(AiChatPrompts.EXECUTE_PERSONA);
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
			sb.append(AiChatPrompts.EXECUTE_TASK_SCHEDULING_RULES);
			sb.append(AiChatPrompts.EXECUTE_TASK_TOOL_RULES);
			sb.append(AiChatPrompts.EXECUTE_COMPLETE_TASK_RULES);
		}
		if (plan != null && plan.needGrowthPlanTools()) {
			sb.append(AiChatPrompts.EXECUTE_GROWTH_TASK_TOOL_RULES);
			if (!plan.needTaskTools()) {
				sb.append(AiChatPrompts.EXECUTE_COMPLETE_TASK_RULES);
			}
		}
		if (plan != null && plan.needPlanProposalTools()) {
			sb.append(AiChatPrompts.EXECUTE_PLAN_PROPOSAL_TOOL_RULES);
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
