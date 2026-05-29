package com.aigp.demo.service.chat;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.chat.AiChatSession;
import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.aigp.demo.domain.enums.ChatMessageRole;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.exception.FeatureUnavailableException;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.repository.AiChatMessageRepository;
import com.aigp.demo.repository.AiChatSessionRepository;
import com.aigp.demo.service.AiChatDataPlan;
import com.aigp.demo.service.AiChatRequestContext;
import com.aigp.demo.service.AiChatUserContextBuilder;
import com.aigp.demo.service.AppUserService;
import com.aigp.demo.service.ChatProviderResolver;
import com.aigp.demo.service.InAppNotificationService;
import com.aigp.demo.service.MediaAssetService;
import com.aigp.demo.service.TaskReminderDueEvaluator;
import com.aigp.demo.service.TaskService;
import com.aigp.demo.service.UserAssistantTaskService;
import com.aigp.demo.service.UserLlmSettingsService;
import com.aigp.demo.support.llm.AiChatPipelineDebugLog;
import com.aigp.demo.support.llm.LlmMessageContentBuilder;
import com.aigp.demo.web.ai.dto.AiChatPlanProposalHint;
import com.aigp.demo.web.ai.dto.AiChatResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 对话单轮流水线：① 判断意图 → ② 选择工具与上下文 → ③ 执行任务 → ④ 落库并返回。
 * <p>
 * 提示词见 {@link AiChatPrompts}；路由规则见 {@link AiChatFastPath}、{@link AiChatDeterministicRoute}、{@link AiChatRouteResolver}。
 */
@Service
@RequiredArgsConstructor
public class AiChatConversationPipeline {

	private final AppProperties appProperties;
	private final AppUserService appUserService;
	private final AiChatSessionRepository aiChatSessionRepository;
	private final AiChatMessageRepository aiChatMessageRepository;
	private final AiChatIntentPhase intentPhase;
	private final AiChatUserContextBuilder aiChatUserContextBuilder;
	private final UserAssistantTaskService userAssistantTaskService;
	private final TaskService taskService;
	private final AiChatExecutionPhase executionPhase;
	private final InAppNotificationService inAppNotificationService;
	private final AiChatPipelineDebugLog pipelineDebugLog;
	private final MediaAssetService mediaAssetService;
	private final ChatProviderResolver chatProviderResolver;
	private final UserLlmSettingsService userLlmSettingsService;

	/**
	 * 处理用户一轮对话并返回 HTTP 响应体（含落库与 WebSocket 推送）。
	 */
	@Transactional
	public AiChatResponse processRound(
			Long userId,
			String userMessage,
			Long sessionId,
			String providerOverride,
			List<Long> imageAssetIds,
			ChatProgressEmitter progress) {
		ChatProgressEmitter emitter = progress == null ? NoopChatProgressEmitter.INSTANCE : progress;
		pipelineDebugLog.step(
				"chat-start",
				"userId=%s sessionId=%s providerOverride=%s",
				userId,
				sessionId,
				providerOverride);

		AppUser user = appUserService.requireActive(userId);
		String providerKey = resolveProviderKey(userId, providerOverride);
		AppProperties.ChatProvider providerConfig = chatProviderResolver.resolveForUser(userId, providerKey);

		AiChatSession session = resolveSession(user, sessionId, providerKey, providerConfig.getModel());
		AiChatRequestContext.setSessionId(session.getId());
		pipelineDebugLog.step(
				"session",
				"sessionId=%s provider=%s model=%s title=%s",
				session.getId(),
				providerKey,
				providerConfig.getModel(),
				session.getTitle());
		if (!StringUtils.hasText(session.getTitle())) {
			session.setTitle(truncate(userMessage, 200));
		}
		emitter.bindSession(session.getId());

		List<Long> imageIds = normalizeImageAssetIds(imageAssetIds);
		if (!imageIds.isEmpty()) {
			mediaAssetService.requireOwned(userId, imageIds);
		}
		List<String> userImageUrls = mediaAssetService.buildPublicUrls(imageIds);

		String historySnippet = buildHistorySnippet(session.getId());
		if (!imageIds.isEmpty()) {
			historySnippet = (historySnippet == null ? "" : historySnippet + "\n")
					+ "（本轮用户附带了 " + imageIds.size() + " 张图片）";
		}
		boolean sessionHasMessages = StringUtils.hasText(historySnippet);
		boolean hasImagesThisTurn = !imageIds.isEmpty();

		HistoryPayload historyPayload = loadHistoryForLlm(userId, session.getId());

		// --- 阶段 1：判断意图 ---
		if (emitter.isActive()) {
			emitter.emit(AiChatProgressCode.ANALYZING);
		}
		AiChatIntentPhase.Result intentResult = intentPhase.resolve(
				providerConfig,
				userMessage,
				historySnippet,
				historyPayload.messages(),
				sessionHasMessages,
				hasImagesThisTurn);
		AiChatRoutePlan route = intentResult.route();
		Optional<AiChatStructuredIntent> structuredIntent = intentResult.structuredIntent();
		String intentHint =
				structuredIntent.map(AiChatStructuredIntent::toExecuteHintBlock).orElse(null);
		pipelineDebugLog.step("route-refined", "capabilities=%s unsupported=%s", route.capabilities(), route.unsupported());

		if (route.unsupportedOnly()) {
			if (emitter.isActive()) {
				emitter.emit(AiChatProgressCode.UNSUPPORTED_FEATURE);
			}
			String reply = AiChatCapabilityCatalog.buildUnsupportedOnlyReply(route.unsupported());
			pipelineDebugLog.step("route", "仅未上线能力，直接回复");
			return phase4Finish(
					user,
					session,
					userMessage,
					reply,
					providerKey,
					providerConfig.getModel(),
					route,
					imageIds,
					userImageUrls,
					AiChatRoundAction.CHAT_ONLY,
					null);
		}

		// --- 阶段 2：选择工具与加载上下文 ---
		AiChatDataPlan plan = route.toDataPlan();
		emitRouteProgress(emitter, plan, structuredIntent);

		String unsupportedHint = AiChatCapabilityCatalog.buildUnsupportedHintForExecute(route.unsupported());

		boolean needContextLoad =
				plan.needTaskList() || plan.needGrowthTaskList() || plan.needUserProfile();
		if (emitter.isActive() && needContextLoad) {
			emitter.emit(AiChatProgressCode.LOADING_CONTEXT);
		}

		String tasksSummary = null;
		if (plan.needTaskList()) {
			String status = StringUtils.hasText(plan.taskListStatus()) ? plan.taskListStatus() : null;
			tasksSummary = userAssistantTaskService.buildTasksSummaryForChat(userId, status, userMessage);
		}

		String growthTasksSummary = null;
		if (plan.needGrowthTaskList()) {
			ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
			growthTasksSummary = taskService.buildGrowthTasksSummaryForChat(userId, LocalDate.now(zone));
		}

		String systemPrompt = aiChatUserContextBuilder.buildSystemPrompt(
				user, plan, tasksSummary, growthTasksSummary, intentHint, unsupportedHint, imageIds);

		List<Map<String, Object>> llmMessages = new ArrayList<>();
		llmMessages.add(Map.of("role", "system", "content", systemPrompt));
		if (plan.needChatHistory()) {
			llmMessages.addAll(historyPayload.messages());
		}
		List<String> currentImageUris = mediaAssetService.toDataUris(userId, imageIds);
		llmMessages.add(userMessageForLlm(userMessage, currentImageUris));

		List<Map<String, Object>> tools = AiChatToolSelection.selectTools(plan);
		pipelineDebugLog.step(
				"execute-pre",
				"llmMessages=%s toolsEnabled=%s chatOnly=%s vision=%s",
				llmMessages.size(),
				!tools.isEmpty(),
				AiChatToolSelection.isChatOnly(plan),
				hasImagesThisTurn || historyPayload.hasImages());

		boolean hasImages = !imageIds.isEmpty() || historyPayload.hasImages();
		AppProperties.ChatProvider executionProvider = resolveExecutionProvider(userId, providerKey, hasImages);
		AiChatExecutionToolGuard toolGuard =
				AiChatExecutionToolGuard.from(route, structuredIntent.orElse(null));

		// --- 阶段 3：执行任务 ---
		AiChatExecutionPhase.Result execution =
				executionPhase.run(userId, executionProvider, llmMessages, tools, toolGuard, emitter);

		// --- 阶段 4：落库并返回 ---
		return phase4Finish(
				user,
				session,
				userMessage,
				execution.text(),
				hasImages ? "vlm" : providerKey,
				executionProvider.getModel(),
				route,
				imageIds,
				userImageUrls,
				execution.roundAction(),
				execution.planProposal());
	}

	/**
	 * 阶段 4：写入本轮 USER/ASSISTANT、推送 WebSocket、组装 {@link AiChatResponse}。
	 */
	private AiChatResponse phase4Finish(
			AppUser user,
			AiChatSession session,
			String userMessage,
			String assistantText,
			String providerKey,
			String model,
			AiChatRoutePlan route,
			List<Long> imageAssetIds,
			List<String> userImageUrls,
			AiChatRoundAction roundAction,
			AiChatPlanProposalHint planProposal) {
		AiChatMessage savedUser = persistMessage(session, ChatMessageRole.USER, userMessage, null, null, null);
		mediaAssetService.linkAssetsToMessage(savedUser.getId(), imageAssetIds);
		AiChatMessage savedAssistant =
				persistMessage(session, ChatMessageRole.ASSISTANT, assistantText, null, null, roundAction);
		aiChatSessionRepository.save(session);

		inAppNotificationService.pushChatReply(
				user.getId(), session.getId(), savedAssistant.getId(), assistantText);

		AiChatResponse response = new AiChatResponse(
				assistantText,
				session.getId(),
				providerKey,
				model,
				savedUser.getId(),
				savedAssistant.getId(),
				roundAction,
				AiChatCapabilityCatalog.toIdStrings(List.copyOf(route.capabilities())),
				AiChatCapabilityCatalog.toIdStrings(route.unsupported()),
				userImageUrls,
				planProposal);
		pipelineDebugLog.endConversationTrace(
				true,
				"sessionId="
						+ session.getId()
						+ " roundAction="
						+ roundAction
						+ " reply="
						+ truncate(assistantText, 500)
						+ (planProposal != null ? " proposalId=" + planProposal.proposalId() : ""));
		return response;
	}

	private static void emitRouteProgress(
			ChatProgressEmitter emitter, AiChatDataPlan plan, Optional<AiChatStructuredIntent> structuredIntent) {
		if (!emitter.isActive()) {
			return;
		}
		if (AiChatToolSelection.isChatOnly(plan)) {
			emitter.emit(AiChatProgressCode.ROUTE_CHAT_ONLY);
		} else {
			emitter.emit(AiChatProgressCode.ROUTE_WITH_TOOLS);
		}
		structuredIntent
				.flatMap(AiChatProgressMessages::expectCode)
				.ifPresent(emitter::emit);
	}

	private String buildHistorySnippet(Long sessionId) {
		int limit = Math.max(2, appProperties.getChat().getPlanningHistorySnippetMessages());
		List<AiChatMessage> recent = aiChatMessageRepository.findBySession_IdOrderByCreatedAtDesc(
				sessionId, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
		if (recent.isEmpty()) {
			return null;
		}
		List<AiChatMessage> chronological = new ArrayList<>(recent);
		Collections.reverse(chronological);
		StringBuilder sb = new StringBuilder();
		for (AiChatMessage m : chronological) {
			if (m.getRole() == ChatMessageRole.USER || m.getRole() == ChatMessageRole.ASSISTANT) {
				sb.append(m.getRole().name().toLowerCase(Locale.ROOT))
						.append(": ")
						.append(truncate(m.getContent(), 300))
						.append('\n');
			}
		}
		return sb.toString();
	}

	private HistoryPayload loadHistoryForLlm(Long userId, Long sessionId) {
		int limit = Math.max(2, appProperties.getChat().getMaxHistoryMessages());
		List<AiChatMessage> recent = aiChatMessageRepository.findBySession_IdOrderByCreatedAtDesc(
				sessionId, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
		List<AiChatMessage> chronological = new ArrayList<>(recent);
		Collections.reverse(chronological);

		List<Long> messageIds = chronological.stream().map(AiChatMessage::getId).toList();
		Map<Long, List<Long>> assetIdsByMessage = mediaAssetService.findMessageAssetIdsByMessageIds(messageIds);

		List<Map<String, Object>> out = new ArrayList<>();
		boolean hasImages = false;
		for (AiChatMessage m : chronological) {
			if (m.getRole() == ChatMessageRole.USER) {
				List<Long> aids = assetIdsByMessage.getOrDefault(m.getId(), List.of());
				if (!aids.isEmpty()) {
					hasImages = true;
				}
				List<String> uris = mediaAssetService.toDataUris(userId, aids);
				out.add(userMessageForLlm(m.getContent() == null ? "" : m.getContent(), uris));
			} else if (m.getRole() == ChatMessageRole.ASSISTANT) {
				out.add(Map.of("role", "assistant", "content", m.getContent() == null ? "" : m.getContent()));
			}
		}
		return new HistoryPayload(out, hasImages);
	}

	private static Map<String, Object> userMessageForLlm(String text, List<String> imageDataUris) {
		Map<String, Object> msg = new java.util.LinkedHashMap<>();
		msg.put("role", "user");
		msg.put("content", LlmMessageContentBuilder.buildUserContent(text, imageDataUris));
		return msg;
	}

	private AppProperties.ChatProvider resolveExecutionProvider(Long userId, String providerKey, boolean hasImages) {
		if (!hasImages) {
			return chatProviderResolver.resolveForUser(userId, providerKey);
		}
		AppProperties.Vlm vlm = appProperties.getVlm();
		if (!StringUtils.hasText(vlm.getApiKey()) || !StringUtils.hasText(vlm.getBaseUrl())) {
			throw new FeatureUnavailableException(
					"VLM", "发送图片对话需配置 VLM_API_KEY 与 VLM_BASE_URL（如通义 qwen-vl）");
		}
		AppProperties.ChatProvider p = new AppProperties.ChatProvider();
		p.setApiKey(vlm.getApiKey());
		p.setBaseUrl(vlm.getBaseUrl());
		p.setModel(StringUtils.hasText(vlm.getModel()) ? vlm.getModel() : "qwen-vl-max-latest");
		return p;
	}

	private static List<Long> normalizeImageAssetIds(List<Long> imageAssetIds) {
		if (imageAssetIds == null || imageAssetIds.isEmpty()) {
			return List.of();
		}
		return imageAssetIds.stream().filter(Objects::nonNull).distinct().toList();
	}

	private record HistoryPayload(List<Map<String, Object>> messages, boolean hasImages) {}

	private AiChatSession resolveSession(AppUser user, Long sessionId, String providerKey, String model) {
		if (sessionId != null) {
			return aiChatSessionRepository
					.findByIdAndUser_Id(sessionId, user.getId())
					.orElseThrow(() -> new NotFoundException("对话会话不存在: id=" + sessionId));
		}
		AiChatSession session = new AiChatSession();
		session.setUser(user);
		session.setProvider(providerKey);
		session.setModel(model);
		return aiChatSessionRepository.save(session);
	}

	private AiChatMessage persistMessage(
			AiChatSession session,
			ChatMessageRole role,
			String content,
			String toolName,
			String toolCallId,
			AiChatRoundAction roundAction) {
		AiChatMessage msg = new AiChatMessage();
		msg.setSession(session);
		msg.setRole(role);
		msg.setContent(content);
		msg.setToolName(toolName);
		msg.setToolCallId(toolCallId);
		if (role == ChatMessageRole.ASSISTANT && roundAction != null) {
			msg.setRoundAction(roundAction.name());
		}
		return aiChatMessageRepository.save(msg);
	}

	private String resolveProviderKey(Long userId, String providerOverride) {
		if (StringUtils.hasText(providerOverride)) {
			return chatProviderResolver.normalizeProviderKey(providerOverride);
		}
		return userLlmSettingsService.resolveEffectiveProviderKey(userId);
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max);
	}
}
