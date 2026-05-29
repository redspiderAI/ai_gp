package com.aigp.demo.service;

import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.chat.AiChatSession;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.repository.AiChatMessageRepository;
import com.aigp.demo.repository.AiChatSessionRepository;
import com.aigp.demo.service.chat.AiChatConversationPipeline;
import com.aigp.demo.service.chat.NoopChatProgressEmitter;
import com.aigp.demo.support.llm.AiChatPipelineDebugLog;
import com.aigp.demo.web.ai.dto.AiChatMessageItemResponse;
import com.aigp.demo.web.ai.dto.AiChatMessageListResponse;
import com.aigp.demo.web.ai.dto.AiChatResponse;
import com.aigp.demo.web.ai.dto.AiChatSessionItemResponse;
import com.aigp.demo.web.ai.dto.AiChatSessionPageResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 对话对外服务：单轮编排委托 {@link AiChatConversationPipeline}；会话/消息列表查询保留在本类。
 */
@Service
@RequiredArgsConstructor
public class AiChatService {

	/** 会话列表默认每页条数 */
	private static final int DEFAULT_SESSION_PAGE_SIZE = 20;
	/** 会话消息默认每页条数 */
	private static final int DEFAULT_MESSAGE_PAGE_SIZE = 50;
	/** 分页 size 上限，防止一次拉全表 */
	private static final int MAX_PAGE_SIZE = 100;

	private final AppUserService appUserService;
	private final AiChatSessionRepository aiChatSessionRepository;
	private final AiChatMessageRepository aiChatMessageRepository;
	private final AiChatConversationPipeline conversationPipeline;
	private final AiChatPipelineDebugLog pipelineDebugLog;
	private final MediaAssetService mediaAssetService;

	@Transactional
	public AiChatResponse chat(
			Long userId,
			String userMessage,
			Long sessionId,
			String providerOverride,
			List<Long> imageAssetIds) {
		pipelineDebugLog.beginConversationTrace(userId, sessionId, userMessage);
		try {
			AiChatRequestContext.setUserMessage(userMessage);
			AiChatRequestContext.setMessageImageAssetIds(normalizeImageAssetIds(imageAssetIds));
			try {
				return conversationPipeline.processRound(
						userId,
						userMessage,
						sessionId,
						providerOverride,
						imageAssetIds,
						NoopChatProgressEmitter.INSTANCE);
			} finally {
				AiChatRequestContext.clear();
			}
		} catch (RuntimeException ex) {
			pipelineDebugLog.endConversationTrace(false, "error: " + ex.getMessage());
			throw ex;
		}
	}

	/**
	 * 分页列出当前用户的 AI 对话会话（按最近更新时间倒序）。
	 */
	@Transactional(readOnly = true)
	public AiChatSessionPageResponse listUserSessions(Long userId, int page, int size) {
		appUserService.requireActive(userId);
		int safePage = Math.max(0, page);
		int safeSize = clampPageSize(size, DEFAULT_SESSION_PAGE_SIZE);
		Page<AiChatSession> result = aiChatSessionRepository.findByUser_IdOrderByUpdatedAtDesc(
				userId, PageRequest.of(safePage, safeSize));
		List<AiChatSessionItemResponse> items =
				result.getContent().stream().map(AiChatSessionItemResponse::fromEntity).toList();
		return new AiChatSessionPageResponse(
				items,
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages(),
				result.hasNext());
	}

	/**
	 * 分页拉取指定会话的历史消息（须属于当前用户；按 createdAt 升序）。
	 */
	@Transactional(readOnly = true)
	public AiChatMessageListResponse listSessionMessages(Long userId, Long sessionId, int page, int size) {
		appUserService.requireActive(userId);
		if (sessionId == null || sessionId <= 0) {
			throw new IllegalArgumentException("sessionId 无效");
		}
		int safePage = Math.max(0, page);
		int safeSize = clampPageSize(size, DEFAULT_MESSAGE_PAGE_SIZE);
		AiChatSession session = aiChatSessionRepository
				.findByIdAndUser_Id(sessionId, userId)
				.orElseThrow(() -> new NotFoundException("对话会话不存在: id=" + sessionId));
		Page<AiChatMessage> result = aiChatMessageRepository.findBySession_IdOrderByCreatedAtAsc(
				sessionId, PageRequest.of(safePage, safeSize));
		List<AiChatMessage> entities = result.getContent();
		List<Long> messageIds = entities.stream().map(AiChatMessage::getId).toList();
		Map<Long, List<Long>> assetIdsByMessage = mediaAssetService.findMessageAssetIdsByMessageIds(messageIds);
		List<AiChatMessageItemResponse> messages = entities.stream()
				.map(m -> {
					List<Long> aids = assetIdsByMessage.getOrDefault(m.getId(), List.of());
					List<String> urls = mediaAssetService.buildPublicUrls(aids);
					return AiChatMessageItemResponse.fromEntity(m, urls);
				})
				.toList();
		return new AiChatMessageListResponse(
				session.getId(),
				session.getTitle(),
				messages.size(),
				messages,
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages(),
				result.hasNext());
	}

	private static int clampPageSize(int size, int defaultSize) {
		if (size <= 0) {
			return defaultSize;
		}
		return Math.min(size, MAX_PAGE_SIZE);
	}

	private static List<Long> normalizeImageAssetIds(List<Long> imageAssetIds) {
		if (imageAssetIds == null || imageAssetIds.isEmpty()) {
			return List.of();
		}
		return imageAssetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
	}
}
