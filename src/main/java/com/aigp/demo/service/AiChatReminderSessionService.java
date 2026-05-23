package com.aigp.demo.service;

import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.chat.AiChatSession;
import com.aigp.demo.domain.enums.ChatMessageRole;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.repository.AiChatMessageRepository;
import com.aigp.demo.repository.AiChatSessionRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时提醒/摘要写入会话：优先用户最近活跃对话，无则创建「任务提醒」兜底会话。
 */
@Service
@RequiredArgsConstructor
public class AiChatReminderSessionService {

	public static final String REMINDER_SESSION_TITLE = "任务提醒";

	/** 每周陪伴回顾推送会话（不参与周总结素材） */
	public static final String WEEKLY_DIGEST_SESSION_TITLE = "本周回顾";

	private static final List<String> SYSTEM_SESSION_TITLES =
			List.of(REMINDER_SESSION_TITLE, WEEKLY_DIGEST_SESSION_TITLE);

	private final AiChatSessionRepository aiChatSessionRepository;
	private final AiChatMessageRepository aiChatMessageRepository;

	/** 写入助手消息后的会话与消息（供站内通知关联 sessionId / messageId）。 */
	public record NoticeDelivery(AiChatSession session, AiChatMessage message) {}

	@Transactional
	public AiChatSession getOrCreateReminderSession(AppUser user, String provider, String model) {
		return getOrCreateSessionByTitle(user, REMINDER_SESSION_TITLE, provider, model);
	}

	@Transactional
	public AiChatSession getOrCreateWeeklyDigestSession(AppUser user, String provider, String model) {
		return getOrCreateSessionByTitle(user, WEEKLY_DIGEST_SESSION_TITLE, provider, model);
	}

	/**
	 * 将提醒/每日摘要写入用户最新活跃会话；若从未聊过则写入「任务提醒」会话。
	 *
	 * @param user 目标用户
	 * @param provider 兜底会话 provider
	 * @param model 兜底会话 model
	 * @param content 助手消息正文
	 */
	@Transactional
	public NoticeDelivery appendAssistantNoticeToLatestSession(
			AppUser user, String provider, String model, String content) {
		AiChatSession session =
				findLatestUserConversationSession(user.getId()).orElseGet(() -> getOrCreateReminderSession(user, provider, model));
		AiChatMessage message = appendAssistantMessage(session, content);
		return new NoticeDelivery(session, message);
	}

	@Transactional(readOnly = true)
	public Optional<AiChatSession> findLatestUserConversationSession(Long userId) {
		List<AiChatSession> rows = aiChatSessionRepository.findLatestUserConversationSessions(
				userId, SYSTEM_SESSION_TITLES, PageRequest.of(0, 1));
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	private AiChatSession getOrCreateSessionByTitle(
			AppUser user, String title, String provider, String model) {
		return aiChatSessionRepository
				.findFirstByUser_IdAndTitle(user.getId(), title)
				.orElseGet(() -> {
					AiChatSession session = new AiChatSession();
					session.setUser(user);
					session.setTitle(title);
					session.setProvider(provider);
					session.setModel(model);
					return aiChatSessionRepository.save(session);
				});
	}

	@Transactional
	public AiChatMessage appendAssistantMessage(AiChatSession session, String content) {
		AiChatMessage msg = new AiChatMessage();
		msg.setSession(session);
		msg.setRole(ChatMessageRole.ASSISTANT);
		msg.setContent(content);
		msg = aiChatMessageRepository.save(msg);
		// 刷新会话 updated_at，保证「最新会话」排序正确
		aiChatSessionRepository.save(session);
		return msg;
	}
}
