package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.chat.AiChatSession;
import com.aigp.demo.domain.enums.ChatMessageRole;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.exception.FeatureUnavailableException;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.repository.AiChatMessageRepository;
import com.aigp.demo.repository.AiChatSessionRepository;
import com.aigp.demo.support.llm.AiChatPipelineDebugLog;
import com.aigp.demo.support.llm.AiChatToolDefinitions;
import com.aigp.demo.support.llm.ChatCompletionResult;
import com.aigp.demo.support.llm.LlmMessageContentBuilder;
import com.aigp.demo.support.llm.OpenAiCompatibleChatClient;
import com.aigp.demo.web.ai.dto.AiChatMessageItemResponse;
import com.aigp.demo.web.ai.dto.AiChatMessageListResponse;
import com.aigp.demo.web.ai.dto.AiChatPlanProposalHint;
import com.aigp.demo.web.ai.dto.AiChatSessionItemResponse;
import com.aigp.demo.web.ai.dto.AiChatSessionPageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.aigp.demo.service.chat.AiChatExecutionToolGuard;
import com.aigp.demo.service.chat.AiChatCapabilityCatalog;
import com.aigp.demo.service.chat.AiChatCapabilityId;
import com.aigp.demo.service.chat.AiChatIntentAnalysisPolicy;
import com.aigp.demo.service.chat.AiChatIntentSignals;
import com.aigp.demo.service.chat.AiChatRoutePlan;
import com.aigp.demo.service.chat.AiChatRouteResolver;
import com.aigp.demo.service.chat.AiChatRoundActionTracker;
import com.aigp.demo.web.ai.dto.AiChatResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 对话编排：① 规划需加载的数据 → ② 按规划拉取上下文并做意图分析 → ③ 多轮工具调用生成回复。
 * 仅将「用户本轮输入」与「最终助手回复」写入 {@code ai_chat_messages}。
 */
@Service
public class AiChatService {

	private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

	private static final Pattern FAKE_TOOL_CALL =
			Pattern.compile("(?is)<\\s*tool_call\\b|</\\s*tool_call\\s*>|<\\s*tool\\s+name\\s*=");
	private static final Pattern FAKE_JSON_TOOL =
			Pattern.compile("(?is)\"action\"\\s*:\\s*\"list_tasks\"|\"action\"\\s*:\\s*\"create_task\"");
	private static final Pattern MOSTLY_ASCII =
			Pattern.compile("^[\\x00-\\x7F\\s<>/=\"'\\-_]+$");

	/** 会话列表默认每页条数 */
	private static final int DEFAULT_SESSION_PAGE_SIZE = 20;
	/** 会话消息默认每页条数 */
	private static final int DEFAULT_MESSAGE_PAGE_SIZE = 50;
	/** 分页 size 上限，防止一次拉全表 */
	private static final int MAX_PAGE_SIZE = 100;

	private final AppProperties appProperties;
	private final AppUserService appUserService;
	private final AiChatSessionRepository aiChatSessionRepository;
	private final AiChatMessageRepository aiChatMessageRepository;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiChatToolExecutor aiChatToolExecutor;
	private final AiChatUserContextBuilder aiChatUserContextBuilder;
	private final UserAssistantTaskService userAssistantTaskService;
	private final TaskService taskService;
	private final AiChatPlanningService aiChatPlanningService;
	private final AiChatRouteResolver routeResolver;
	private final InAppNotificationService inAppNotificationService;
	private final AiChatPipelineDebugLog pipelineDebugLog;
	private final MediaAssetService mediaAssetService;
	private final ChatProviderResolver chatProviderResolver;
	private final UserLlmSettingsService userLlmSettingsService;
	private final ObjectMapper objectMapper;

	public AiChatService(
			AppProperties appProperties,
			AppUserService appUserService,
			AiChatSessionRepository aiChatSessionRepository,
			AiChatMessageRepository aiChatMessageRepository,
			OpenAiCompatibleChatClient openAiCompatibleChatClient,
			AiChatToolExecutor aiChatToolExecutor,
			AiChatUserContextBuilder aiChatUserContextBuilder,
			UserAssistantTaskService userAssistantTaskService,
			TaskService taskService,
			AiChatPlanningService aiChatPlanningService,
			AiChatRouteResolver routeResolver,
			InAppNotificationService inAppNotificationService,
			AiChatPipelineDebugLog pipelineDebugLog,
			MediaAssetService mediaAssetService,
			ChatProviderResolver chatProviderResolver,
			UserLlmSettingsService userLlmSettingsService,
			ObjectMapper objectMapper) {
		this.appProperties = appProperties;
		this.appUserService = appUserService;
		this.aiChatSessionRepository = aiChatSessionRepository;
		this.aiChatMessageRepository = aiChatMessageRepository;
		this.openAiCompatibleChatClient = openAiCompatibleChatClient;
		this.aiChatToolExecutor = aiChatToolExecutor;
		this.aiChatUserContextBuilder = aiChatUserContextBuilder;
		this.userAssistantTaskService = userAssistantTaskService;
		this.taskService = taskService;
		this.aiChatPlanningService = aiChatPlanningService;
		this.routeResolver = routeResolver;
		this.inAppNotificationService = inAppNotificationService;
		this.pipelineDebugLog = pipelineDebugLog;
		this.mediaAssetService = mediaAssetService;
		this.chatProviderResolver = chatProviderResolver;
		this.userLlmSettingsService = userLlmSettingsService;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public AiChatResponse chat(
			Long userId,
			String userMessage,
			Long sessionId,
			String providerOverride,
			List<Long> imageAssetIds) {
		pipelineDebugLog.beginConversationTrace(userId, sessionId, userMessage);
		try {
			return doChat(userId, userMessage, sessionId, providerOverride, imageAssetIds);
		} catch (RuntimeException ex) {
			pipelineDebugLog.endConversationTrace(false, "error: " + ex.getMessage());
			throw ex;
		}
	}

	private AiChatResponse doChat(
			Long userId,
			String userMessage,
			Long sessionId,
			String providerOverride,
			List<Long> imageAssetIds) {
		List<Long> imageIds = normalizeImageAssetIds(imageAssetIds);
		AiChatRequestContext.setUserMessage(userMessage);
		AiChatRequestContext.setMessageImageAssetIds(imageIds);
		try {
			return doChatInner(userId, userMessage, sessionId, providerOverride, imageIds);
		} finally {
			AiChatRequestContext.clear();
		}
	}

	private AiChatResponse doChatInner(
			Long userId, String userMessage, Long sessionId, String providerOverride, List<Long> imageAssetIds) {
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

		if (!imageAssetIds.isEmpty()) {
			mediaAssetService.requireOwned(userId, imageAssetIds);
		}
		List<String> userImageUrls = mediaAssetService.buildPublicUrls(imageAssetIds);

		String historySnippet = buildHistorySnippet(session.getId());
		if (!imageAssetIds.isEmpty()) {
			historySnippet = (historySnippet == null ? "" : historySnippet + "\n")
					+ "（本轮用户附带了 " + imageAssetIds.size() + " 张图片）";
		}
		boolean sessionHasMessages = StringUtils.hasText(historySnippet);
		boolean hasImagesThisTurn = !imageAssetIds.isEmpty();
		AiChatRoutePlan route = aiChatPlanningService.planRoute(
				providerConfig, userMessage, historySnippet, sessionHasMessages, hasImagesThisTurn);
		route = routeResolver.refine(route, userMessage, historySnippet, sessionHasMessages);
		pipelineDebugLog.step("route-refined", "capabilities=%s unsupported=%s", route.capabilities(), route.unsupported());

		if (route.unsupportedOnly()) {
			String reply = AiChatCapabilityCatalog.buildUnsupportedOnlyReply(route.unsupported());
			pipelineDebugLog.step("route", "仅未上线能力，直接回复");
			return persistRoundAndRespond(
					user,
					session,
					userMessage,
					reply,
					providerKey,
					providerConfig.getModel(),
					route,
					imageAssetIds,
					userImageUrls);
		}

		HistoryPayload historyPayload = loadHistoryForLlm(userId, session.getId());
		List<Map<String, Object>> sessionHistoryForIntent = historyPayload.messages();

		String intentHint = null;
		if (AiChatIntentAnalysisPolicy.shouldAnalyzeIntent(appProperties.getChat(), route)) {
			intentHint = aiChatPlanningService.analyzeUserIntent(
					providerConfig, userMessage, sessionHistoryForIntent);
			route = alignRouteWithIntentHint(route, intentHint);
			pipelineDebugLog.step("route-after-intent", "capabilities=%s", route.capabilities());
		} else {
			pipelineDebugLog.step(
					"intent",
					"跳过：policy=%s fastOrDeterministic=%s planProposal=%s",
					appProperties.getChat().isIntentAnalysisEnabled() ? "legacy-off" : "default-skip",
					route.skipIntentAnalysis(),
					route.hasCapability(AiChatCapabilityId.PLAN_PROPOSAL));
		}

		AiChatDataPlan plan = route.toDataPlan();
		String unsupportedHint = AiChatCapabilityCatalog.buildUnsupportedHintForExecute(route.unsupported());

		String tasksSummary = null;
		if (plan.needTaskList()) {
			String status = StringUtils.hasText(plan.taskListStatus()) ? plan.taskListStatus() : null;
			tasksSummary = userAssistantTaskService.buildTasksSummaryForChat(userId, status, userMessage);
		}

		String growthTasksSummary = null;
		if (plan.needGrowthTaskList()) {
			var u = appUserService.requireActive(userId);
			ZoneId zone = TaskReminderDueEvaluator.resolveZone(u.getTimezone());
			growthTasksSummary = taskService.buildGrowthTasksSummaryForChat(userId, LocalDate.now(zone));
		}

		String systemPrompt = aiChatUserContextBuilder.buildSystemPrompt(
				user, plan, tasksSummary, growthTasksSummary, intentHint, unsupportedHint, imageAssetIds);

		List<Map<String, Object>> llmMessages = new ArrayList<>();
		llmMessages.add(Map.of("role", "system", "content", systemPrompt));
		if (plan.needChatHistory()) {
			llmMessages.addAll(sessionHistoryForIntent);
		}
		List<String> currentImageUris = mediaAssetService.toDataUris(userId, imageAssetIds);
		llmMessages.add(userMessageForLlm(userMessage, currentImageUris));

		boolean hasImages = !imageAssetIds.isEmpty() || historyPayload.hasImages();
		AppProperties.ChatProvider executionProvider = resolveExecutionProvider(userId, providerKey, hasImages);

		List<Map<String, Object>> tools = buildExecutionTools(plan);
		pipelineDebugLog.step(
				"execute-pre",
				"llmMessages=%s toolsEnabled=%s vision=%s",
				llmMessages.size(),
				!tools.isEmpty(),
				hasImages);
		AiChatExecutionToolGuard toolGuard = AiChatExecutionToolGuard.from(route, intentHint);
		ExecutionResult execution = runExecutionLoop(userId, executionProvider, llmMessages, tools, toolGuard);
		String assistantText = execution.text();
		AiChatPlanProposalHint planProposal = execution.planProposal();
		AiChatMessage savedUser = persistMessage(session, ChatMessageRole.USER, userMessage, null, null, null);
		mediaAssetService.linkAssetsToMessage(savedUser.getId(), imageAssetIds);
		AiChatMessage savedAssistant =
				persistMessage(session, ChatMessageRole.ASSISTANT, assistantText, null, null, execution.roundAction());
		aiChatSessionRepository.save(session);

		inAppNotificationService.pushChatReply(
				userId, session.getId(), savedAssistant.getId(), assistantText);

		String executionModel = executionProvider.getModel();
		AiChatResponse response = new AiChatResponse(
				assistantText,
				session.getId(),
				hasImages ? "vlm" : providerKey,
				executionModel,
				savedUser.getId(),
				savedAssistant.getId(),
				execution.roundAction(),
				AiChatCapabilityCatalog.toIdStrings(List.copyOf(route.capabilities())),
				AiChatCapabilityCatalog.toIdStrings(route.unsupported()),
				userImageUrls,
				planProposal);
		pipelineDebugLog.endConversationTrace(
				true,
				"sessionId="
						+ session.getId()
						+ " roundAction="
						+ execution.roundAction()
						+ " reply="
						+ truncate(assistantText, 500)
						+ (planProposal != null ? " proposalId=" + planProposal.proposalId() : ""));
		return response;
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

	/**
	 * 执行阶段：可含工具多轮；中间 assistant/tool 消息仅存在于内存，不落库。
	 */
	private record ExecutionResult(
			String text, AiChatPlanProposalHint planProposal, AiChatRoundAction roundAction) {}

	private static List<Map<String, Object>> buildExecutionTools(AiChatDataPlan plan) {
		List<Map<String, Object>> tools = new ArrayList<>();
		if (plan.needTaskTools()) {
			tools.addAll(AiChatToolDefinitions.taskTools());
		}
		if (plan.needGrowthPlanTools()) {
			tools.addAll(AiChatToolDefinitions.growthTaskTools());
		}
		if (plan.needPlanProposalTools()) {
			tools.addAll(AiChatToolDefinitions.planProposalTools());
		}
		return tools;
	}

	private ExecutionResult runExecutionLoop(
			Long userId,
			AppProperties.ChatProvider providerConfig,
			List<Map<String, Object>> messages,
			List<Map<String, Object>> tools,
			AiChatExecutionToolGuard toolGuard) {
		if (tools == null || tools.isEmpty()) {
			ChatCompletionResult result =
					openAiCompatibleChatClient.chat(providerConfig, messages, null, "execute");
			return new ExecutionResult(
					finalizeAssistantText(result.content()), null, AiChatRoundAction.CHAT_ONLY);
		}
		AiChatPlanProposalHint planProposal = null;
		AiChatRoundActionTracker roundActionTracker = new AiChatRoundActionTracker(objectMapper);
		int maxRounds = Math.max(1, appProperties.getChat().getMaxToolRounds());
		for (int round = 0; round < maxRounds; round++) {
			String phase = "execute-r" + (round + 1);
			pipelineDebugLog.step(phase, "开始第 %s/%s 轮工具对话", round + 1, maxRounds);
			ChatCompletionResult result = openAiCompatibleChatClient.chat(providerConfig, messages, tools, phase);
			if (!result.hasToolCalls()) {
				AiChatRoundAction roundAction = roundActionTracker.get();
				if (toolGuard.shouldRetryMissingMutationTools(result.content(), roundAction)) {
					toolGuard.markRetried();
					pipelineDebugLog.step(phase, "未调任务工具却声称已操作，追加一轮强制补调");
					messages.add(Map.of("role", "system", "content", toolGuard.retrySystemNudge()));
					continue;
				}
				pipelineDebugLog.step(phase, "无 tool_calls，结束执行环");
				String text = finalizeAssistantText(result.content());
				if (toolGuard.needsFallbackAfterMissingTools(text, roundAction)) {
					text = toolGuard.fallbackWhenStillMissingTools();
				}
				return new ExecutionResult(text, planProposal, roundAction);
			}

			Map<String, Object> assistantMsg = new LinkedHashMap<>();
			assistantMsg.put("role", "assistant");
			if (StringUtils.hasText(result.content())) {
				assistantMsg.put("content", result.content());
			}
			List<Map<String, Object>> toolCallMaps = new ArrayList<>();
			for (ChatCompletionResult.ToolCallPayload tc : result.toolCalls()) {
				Map<String, Object> fn = new LinkedHashMap<>();
				fn.put("name", tc.name());
				fn.put("arguments", tc.argumentsJson());
				Map<String, Object> call = new LinkedHashMap<>();
				call.put("id", tc.id());
				call.put("type", "function");
				call.put("function", fn);
				toolCallMaps.add(call);
			}
			assistantMsg.put("tool_calls", toolCallMaps);
			if (StringUtils.hasText(result.reasoningContent())) {
				assistantMsg.put("reasoning_content", result.reasoningContent());
			}
			messages.add(assistantMsg);

			for (ChatCompletionResult.ToolCallPayload tc : result.toolCalls()) {
				roundActionTracker.record(tc.name(), tc.argumentsJson());
				String toolResult =
						aiChatToolExecutor.execute(userId, tc.name(), tc.argumentsJson(), phase, round + 1);
				if ("propose_growth_plan".equals(tc.name())) {
					AiChatPlanProposalHint hint = parsePlanProposalHint(toolResult);
					if (hint != null) {
						planProposal = hint;
					}
				}
				messages.add(Map.of("role", "tool", "tool_call_id", tc.id(), "content", toolResult));
			}
		}
		pipelineDebugLog.step("execute", "超过最大工具轮次 max=%s", maxRounds);
		throw new IllegalStateException("任务工具调用轮次过多，请简化问题后重试");
	}

	private static String textOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asText().trim();
		return s.isEmpty() ? null : s;
	}

	private AiChatPlanProposalHint parsePlanProposalHint(String toolResultJson) {
		if (!StringUtils.hasText(toolResultJson)) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(toolResultJson);
			if (!node.path("ok").asBoolean(false) || node.path("proposalId").asLong(0) <= 0) {
				return null;
			}
			return new AiChatPlanProposalHint(
					node.path("proposalId").asLong(),
					node.path("status").asText("PENDING"),
					node.path("goalTitle").asText(""),
					node.path("summary").asText(""),
					node.path("dayCount").asInt(0),
					textOrNull(node.path("startDate")),
					textOrNull(node.path("endDate")),
					node.path("dailyReminderTime").asText("08:00"));
		} catch (Exception e) {
			log.warn("解析计划草案工具结果失败: {}", e.getMessage());
			return null;
		}
	}

	private static String finalizeAssistantText(String content) {
		if (!StringUtils.hasText(content)) {
			return "抱歉，我暂时无法生成回复，请稍后再试。";
		}
		String text = content.trim();
		if (text.startsWith("[内部意图分析")) {
			int idx = text.indexOf('\n');
			return idx > 0 && idx < text.length() - 1 ? text.substring(idx + 1).trim() : text;
		}
		if (FAKE_TOOL_CALL.matcher(text).find() || FAKE_JSON_TOOL.matcher(text).find()) {
			return "抱歉，我这边没能正确查到你的待办。请再说一次，例如「列出我未来几天的计划」。";
		}
		if (MOSTLY_ASCII.matcher(text).matches() && text.length() < 200) {
			return "抱歉，我这边没能正确生成回复，请换个说法再试一次。";
		}
		return text;
	}

	private AiChatResponse persistRoundAndRespond(
			AppUser user,
			AiChatSession session,
			String userMessage,
			String assistantText,
			String providerKey,
			String model,
			AiChatRoutePlan route,
			List<Long> imageAssetIds,
			List<String> userImageUrls) {
		AiChatMessage savedUser = persistMessage(session, ChatMessageRole.USER, userMessage, null, null, null);
		mediaAssetService.linkAssetsToMessage(savedUser.getId(), imageAssetIds);
		AiChatMessage savedAssistant =
				persistMessage(session, ChatMessageRole.ASSISTANT, assistantText, null, null, AiChatRoundAction.CHAT_ONLY);
		aiChatSessionRepository.save(session);
		inAppNotificationService.pushChatReply(
				user.getId(), session.getId(), savedAssistant.getId(), assistantText);
		pipelineDebugLog.endConversationTrace(
				true, "sessionId=" + session.getId() + " reply=" + truncate(assistantText, 500));
		return new AiChatResponse(
				assistantText,
				session.getId(),
				providerKey,
				model,
				savedUser.getId(),
				savedAssistant.getId(),
				AiChatRoundAction.CHAT_ONLY,
				AiChatCapabilityCatalog.toIdStrings(List.copyOf(route.capabilities())),
				AiChatCapabilityCatalog.toIdStrings(route.unsupported()),
				userImageUrls,
				null);
	}

	private static AiChatRoutePlan alignRouteWithIntentHint(AiChatRoutePlan route, String intentHint) {
		if (route == null || !StringUtils.hasText(intentHint)) {
			return route;
		}
		java.util.Set<AiChatCapabilityId> caps = new java.util.LinkedHashSet<>(route.capabilities());
		boolean changed = false;
		if (AiChatIntentSignals.suggestsTaskTools(intentHint)
				&& !route.hasCapability(AiChatCapabilityId.ASSISTANT_TASKS)) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
			changed = true;
		}
		if (AiChatIntentSignals.suggestsPlanProposalTools(intentHint)
				&& !route.hasCapability(AiChatCapabilityId.PLAN_PROPOSAL)) {
			caps.add(AiChatCapabilityId.PLAN_PROPOSAL);
			changed = true;
		}
		if (AiChatIntentSignals.suggestsGrowthPlanTools(intentHint)
				&& !route.hasCapability(AiChatCapabilityId.GROWTH_PLAN_TASKS)) {
			caps.add(AiChatCapabilityId.GROWTH_PLAN_TASKS);
			changed = true;
		}
		if (!changed) {
			return route;
		}
		return new AiChatRoutePlan(caps, route.unsupported(), route.taskListStatus(), route.reason());
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
		Map<String, Object> msg = new LinkedHashMap<>();
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
