package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiChatProfilePhraseSignalsTest {

	@Test
	void recognizesWhoAmIVariants() {
		assertTrue(AiChatProfilePhraseSignals.looksLikeProfileQuery("你好我是谁"));
		assertTrue(AiChatProfilePhraseSignals.looksLikeProfileQuery("我叫什么"));
		assertTrue(AiChatProfilePhraseSignals.looksLikeProfileQuery("介绍一下我"));
	}

	@Test
	void recognizesProfileFieldQuestions() {
		assertTrue(AiChatProfilePhraseSignals.looksLikeProfileQuery("我的爱好是什么"));
		assertFalse(AiChatProfilePhraseSignals.looksLikeProfileQuery("你好"));
	}
}
