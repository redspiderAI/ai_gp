package com.aigp.demo.service.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GrowthPlanProposalPayloadParserTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void parseValidPayload() throws Exception {
		String json =
				"""
				{
				  "version": 1,
				  "goalTitle": "一个月六级",
				  "summary": "四周冲刺",
				  "dailyReminderTime": "08:30",
				  "days": [
				    {"dayIndex": 1, "scheduledDate": "2026-05-20", "title": "词汇", "estimatedMinutes": 60},
				    {"dayIndex": 2, "scheduledDate": "2026-05-21", "title": "听力", "estimatedMinutes": 90}
				  ]
				}
				""";
		GrowthPlanProposalPayload payload =
				GrowthPlanProposalPayloadParser.parse(objectMapper.readTree(json));
		assertEquals("一个月六级", payload.goalTitle());
		assertEquals(2, payload.days().size());
		assertEquals(8, payload.dailyReminderTime().getHour());
		assertEquals(30, payload.dailyReminderTime().getMinute());
	}

	@Test
	void rejectEmptyDays() throws Exception {
		String json =
				"""
				{"version":1,"goalTitle":"x","summary":"y","days":[]}
				""";
		assertThrows(
				IllegalArgumentException.class,
				() -> GrowthPlanProposalPayloadParser.parse(objectMapper.readTree(json)));
	}
}
