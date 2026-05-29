package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aigp.demo.service.chat.AiChatStructuredIntent.AssistantTaskOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.GrowthTaskOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.PlanOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.PrimaryGoal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiChatIntentResolverTest {

	private final AiChatIntentResolver resolver = new AiChatIntentResolver(new ObjectMapper());

	@Test
	void parsesValidStructuredJson() {
		String raw =
				"""
				{"primaryGoal":"ASSISTANT_TASK","assistantTaskOp":"CREATE","growthTaskOp":"NONE","planOp":"NONE","reminderNeedsDueAt":true,"completeNeedsDisambiguation":false}
				""";
		var intent = resolver.parse(raw);
		assertTrue(intent.isPresent());
		assertEquals(PrimaryGoal.ASSISTANT_TASK, intent.get().primaryGoal());
		assertEquals(AssistantTaskOp.CREATE, intent.get().assistantTaskOp());
		assertTrue(intent.get().reminderNeedsDueAt());
		assertTrue(intent.get().suggestsTaskMutation());
	}

	@Test
	void rejectsFreeTextAndUnknownEnum() {
		assertFalse(resolver.parse("用户想要记一个待办").isPresent());
		assertFalse(resolver.parse("{\"primaryGoal\":\"MAKE_COFFEE\"}").isPresent());
	}

	@Test
	void planProposalEnumEnablesPlanTools() {
		String raw =
				"""
				{"primaryGoal":"PLAN_PROPOSAL","assistantTaskOp":"NONE","growthTaskOp":"NONE","planOp":"PROPOSE","reminderNeedsDueAt":false,"completeNeedsDisambiguation":false}
				""";
		var intent = resolver.parse(raw);
		assertTrue(intent.isPresent());
		assertEquals(PlanOp.PROPOSE, intent.get().planOp());
		assertTrue(AiChatIntentSignals.suggestsPlanProposalTools(intent.get()));
		assertEquals(GrowthTaskOp.NONE, intent.get().growthTaskOp());
	}
}
