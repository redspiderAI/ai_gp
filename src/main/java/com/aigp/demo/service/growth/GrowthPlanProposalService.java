package com.aigp.demo.service.growth;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.GoalStatus;
import com.aigp.demo.domain.enums.GrowthPlanProposalStatus;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import com.aigp.demo.domain.goal.Goal;
import com.aigp.demo.domain.growth.GrowthPlanProposal;
import com.aigp.demo.domain.plan.Plan;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.exception.ConflictException;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.repository.GrowthPlanProposalRepository;
import com.aigp.demo.repository.UserAssistantTaskRepository;
import com.aigp.demo.service.AiChatRequestContext;
import com.aigp.demo.service.AppUserService;
import com.aigp.demo.service.GoalService;
import com.aigp.demo.service.PlanService;
import com.aigp.demo.service.TaskService;
import com.aigp.demo.web.growth.dto.GrowthPlanProposalConfirmResponse;
import com.aigp.demo.web.growth.dto.GrowthPlanProposalDetailResponse;
import com.aigp.demo.web.growth.dto.GrowthPlanProposalDayResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 成长计划草案：AI 提交待确认方案；用户确认后写入 goals/plans/tasks，并同步助手待办用于每日提醒。
 */
@Service
@RequiredArgsConstructor
public class GrowthPlanProposalService {

	private static final int PROPOSAL_TTL_DAYS = 7;
	private static final String AI_PROMPT_VERSION = "growth-plan-proposal-v1";

	private final GrowthPlanProposalRepository growthPlanProposalRepository;
	private final UserAssistantTaskRepository userAssistantTaskRepository;
	private final AppUserService appUserService;
	private final GoalService goalService;
	private final PlanService planService;
	private final TaskService taskService;
	private final ObjectMapper objectMapper;
	private final AppProperties appProperties;

	/**
	 * 供 AI 工具调用：校验 JSON 并保存 PENDING 草案。
	 *
	 * @return 工具 JSON 字符串（含 proposalId）
	 */
	@Transactional
	public String proposeFromToolJson(Long userId, JsonNode planRoot) {
		GrowthPlanProposalPayload payload = GrowthPlanProposalPayloadParser.parse(planRoot);
		AppUser user = appUserService.requireActive(userId);
		GrowthPlanProposal entity = new GrowthPlanProposal();
		entity.setUser(user);
		Long sessionId = AiChatRequestContext.getSessionId();
		if (sessionId != null && sessionId > 0) {
			entity.setSessionId(sessionId);
		}
		entity.setStatus(GrowthPlanProposalStatus.PENDING);
		entity.setPayloadJson(writePayload(payload));
		entity.setExpiresAt(LocalDateTime.now().plusDays(PROPOSAL_TTL_DAYS));
		growthPlanProposalRepository.save(entity);

		LocalDate start = payload.days().get(0).scheduledDate();
		LocalDate end = payload.days().get(payload.days().size() - 1).scheduledDate();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("proposalId", entity.getId());
		result.put("status", GrowthPlanProposalStatus.PENDING.name());
		result.put("goalTitle", payload.goalTitle());
		result.put("summary", payload.summary());
		result.put("dayCount", payload.days().size());
		result.put("startDate", start.toString());
		result.put("endDate", end.toString());
		result.put("deadline", payload.deadline().toString());
		result.put("dailyReminderTime", payload.dailyReminderTime().toString());
		result.put(
				"message",
				"草案已保存，请向用户展示计划摘要并明确：需在 App 内确认后才会开始每日提醒与任务入库");
		return toJson(result);
	}

	@Transactional(readOnly = true)
	public GrowthPlanProposalDetailResponse getDetail(Long userId, Long proposalId) {
		GrowthPlanProposal entity = requireOwnedPendingOrTerminal(userId, proposalId);
		GrowthPlanProposalPayload payload = readPayload(entity.getPayloadJson());
		List<GrowthPlanProposalDayResponse> days = payload.days().stream()
				.map(d -> new GrowthPlanProposalDayResponse(
						d.dayIndex(),
						d.scheduledDate(),
						d.title(),
						d.description(),
						d.estimatedMinutes()))
				.toList();
		return new GrowthPlanProposalDetailResponse(
				entity.getId(),
				entity.getStatus().name(),
				entity.getSessionId(),
				payload.goalTitle(),
				payload.goalDescription(),
				payload.domain() == null ? null : payload.domain().name(),
				payload.deadline(),
				payload.summary(),
				payload.dailyReminderTime().toString(),
				days,
				entity.getGoalId(),
				entity.getPlanId(),
				entity.getExpiresAt(),
				entity.getCreatedAt());
	}

	/**
	 * 用户确认草案：创建目标、计划版本、每日成长任务，并写入带 dueAt 的助手待办以触发定时提醒。
	 */
	@Transactional
	public GrowthPlanProposalConfirmResponse confirm(Long userId, Long proposalId) {
		GrowthPlanProposal entity = requireOwned(userId, proposalId);
		if (entity.getStatus() != GrowthPlanProposalStatus.PENDING) {
			throw new ConflictException("该计划草案已处理，无法重复确认");
		}
		if (entity.getExpiresAt() != null && LocalDateTime.now().isAfter(entity.getExpiresAt())) {
			throw new ConflictException("计划草案已过期，请重新让 AI 生成");
		}
		GrowthPlanProposalPayload payload = readPayload(entity.getPayloadJson());
		AppUser user = appUserService.requireActive(userId);

		Goal goal = goalService.create(
				user,
				payload.goalTitle(),
				payload.goalDescription(),
				payload.domain(),
				null,
				payload.deadline());
		goal = goalService.updateStatus(userId, goal.getId(), GoalStatus.ACTIVE);

		Plan plan = planService.createNextPlanVersion(userId, goal.getId(), AI_PROMPT_VERSION);

		int growthTasks = 0;
		int reminderTasks = 0;
		for (GrowthPlanProposalDayItem day : payload.days()) {
			taskService.scheduleTask(
					userId,
					plan,
					goal,
					null,
					day.title(),
					day.description(),
					day.scheduledDate(),
					day.estimatedMinutes());
			growthTasks++;
			createReminderAssistantTask(user, day, payload.dailyReminderTime());
			reminderTasks++;
		}

		entity.setStatus(GrowthPlanProposalStatus.CONFIRMED);
		entity.setGoalId(goal.getId());
		entity.setPlanId(plan.getId());
		entity.setConfirmedAt(LocalDateTime.now());
		growthPlanProposalRepository.save(entity);

		return new GrowthPlanProposalConfirmResponse(
				entity.getId(),
				goal.getId(),
				plan.getId(),
				growthTasks,
				reminderTasks,
				payload.dailyReminderTime().toString(),
				"计划已生效：成长任务已入库，每日 "
						+ payload.dailyReminderTime()
						+ " 将通过助手待办提醒（请保持「任务提醒」通知开启）");
	}

	@Transactional
	public void reject(Long userId, Long proposalId) {
		GrowthPlanProposal entity = requireOwned(userId, proposalId);
		if (entity.getStatus() != GrowthPlanProposalStatus.PENDING) {
			throw new ConflictException("该计划草案已处理");
		}
		entity.setStatus(GrowthPlanProposalStatus.REJECTED);
		entity.setRejectedAt(LocalDateTime.now());
		growthPlanProposalRepository.save(entity);
	}

	private void createReminderAssistantTask(AppUser user, GrowthPlanProposalDayItem day, LocalTime reminderTime) {
		LocalTime time = reminderTime != null ? reminderTime : defaultReminderTime();
		LocalDateTime dueAt = LocalDateTime.of(day.scheduledDate(), time);
		UserAssistantTask task = new UserAssistantTask();
		task.setUser(user);
		task.setTitle("[学习计划] " + day.title());
		String desc = day.description();
		if (StringUtils.hasText(desc)) {
			task.setDescription(desc);
		} else {
			task.setDescription("第 " + day.dayIndex() + " 天 · 预计 " + day.estimatedMinutes() + " 分钟");
		}
		task.setDueDate(day.scheduledDate());
		task.setDueAt(dueAt);
		task.setStatus(UserAssistantTaskStatus.OPEN);
		userAssistantTaskRepository.save(task);
	}

	private LocalTime defaultReminderTime() {
		String configured = appProperties.getTaskReminder().getDefaultDueDateReminderTime();
		if (StringUtils.hasText(configured)) {
			try {
				return LocalTime.parse(configured.trim());
			} catch (Exception ignored) {
				// 配置非法时回退 08:00
			}
		}
		return LocalTime.of(8, 0);
	}

	private GrowthPlanProposal requireOwned(Long userId, Long proposalId) {
		return growthPlanProposalRepository
				.findByIdAndUser_Id(proposalId, userId)
				.orElseThrow(() -> new NotFoundException("计划草案不存在: id=" + proposalId));
	}

	private GrowthPlanProposal requireOwnedPendingOrTerminal(Long userId, Long proposalId) {
		return requireOwned(userId, proposalId);
	}

	private GrowthPlanProposalPayload readPayload(String json) {
		try {
			return GrowthPlanProposalPayloadParser.parse(objectMapper.readTree(json));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("计划草案数据损坏");
		}
	}

	private String writePayload(GrowthPlanProposalPayload payload) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("version", payload.version());
		root.put("goalTitle", payload.goalTitle());
		root.put("goalDescription", payload.goalDescription());
		root.put("domain", payload.domain() == null ? null : payload.domain().name());
		root.put("deadline", payload.deadline() == null ? null : payload.deadline().toString());
		root.put("summary", payload.summary());
		root.put("dailyReminderTime", payload.dailyReminderTime().toString());
		List<Map<String, Object>> days = new ArrayList<>();
		for (GrowthPlanProposalDayItem d : payload.days()) {
			Map<String, Object> day = new LinkedHashMap<>();
			day.put("dayIndex", d.dayIndex());
			day.put("scheduledDate", d.scheduledDate().toString());
			day.put("title", d.title());
			day.put("description", d.description());
			day.put("estimatedMinutes", d.estimatedMinutes());
			days.add(day);
		}
		root.put("days", days);
		return toJson(root);
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			return "{\"error\":\"序列化失败\"}";
		}
	}
}
