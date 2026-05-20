package com.aigp.demo.service.growth;

import com.aigp.demo.domain.enums.GrowthDomain;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * AI 工具 {@code propose_growth_plan} 与表 {@code growth_plan_proposals.payload_json} 的结构（version=1）。
 */
public record GrowthPlanProposalPayload(
		int version,
		String goalTitle,
		String goalDescription,
		GrowthDomain domain,
		LocalDate deadline,
		String summary,
		LocalTime dailyReminderTime,
		List<GrowthPlanProposalDayItem> days) {}
