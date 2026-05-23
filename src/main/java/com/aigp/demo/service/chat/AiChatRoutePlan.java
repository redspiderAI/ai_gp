package com.aigp.demo.service.chat;

import com.aigp.demo.service.AiChatDataPlan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构化对话路由：规划阶段选能力，执行阶段按能力加载上下文与工具。
 */
public record AiChatRoutePlan(
		Set<AiChatCapabilityId> capabilities,
		List<AiChatCapabilityId> unsupported,
		String taskListStatus,
		String reason) {

	public boolean hasCapability(AiChatCapabilityId id) {
		return capabilities != null && capabilities.contains(id);
	}

	public boolean needsExecution() {
		return capabilities != null
				&& (hasCapability(AiChatCapabilityId.ASSISTANT_TASKS)
						|| hasCapability(AiChatCapabilityId.PLAN_PROPOSAL)
						|| hasCapability(AiChatCapabilityId.GROWTH_PLAN_TASKS)
						|| hasCapability(AiChatCapabilityId.CHAT)
						|| hasCapability(AiChatCapabilityId.USER_PROFILE));
	}

	/** 仅请求未上线能力、且没有可执行的助手/画像等能力时，直接返回固定话术 */
	public boolean unsupportedOnly() {
		boolean noExecutable =
				capabilities == null
						|| capabilities.isEmpty()
						|| (capabilities.size() == 1 && hasCapability(AiChatCapabilityId.CHAT));
		return noExecutable && unsupported != null && !unsupported.isEmpty();
	}

	public AiChatDataPlan toDataPlan() {
		boolean needHistory = hasCapability(AiChatCapabilityId.CHAT_HISTORY);
		boolean needProfile = hasCapability(AiChatCapabilityId.USER_PROFILE);
		boolean assistant = hasCapability(AiChatCapabilityId.ASSISTANT_TASKS);
		boolean planProposal = hasCapability(AiChatCapabilityId.PLAN_PROPOSAL);
		boolean growthPlan = hasCapability(AiChatCapabilityId.GROWTH_PLAN_TASKS);
		String status = taskListStatus;
		if (assistant && (status == null || status.isBlank())) {
			status = "OPEN";
		}
		return new AiChatDataPlan(
				needHistory,
				needProfile,
				assistant,
				assistant,
				planProposal,
				growthPlan,
				growthPlan,
				status,
				reason == null ? "" : reason);
	}

	public static AiChatRoutePlan defaults() {
		Set<AiChatCapabilityId> caps = new LinkedHashSet<>();
		caps.add(AiChatCapabilityId.CHAT);
		caps.add(AiChatCapabilityId.CHAT_HISTORY);
		caps.add(AiChatCapabilityId.USER_PROFILE);
		caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
		return new AiChatRoutePlan(caps, List.of(), "OPEN", "默认加载常用能力");
	}
}
