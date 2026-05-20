package com.aigp.demo.service.push;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.user.UserPushDevice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * uni-push 2.0：调用 DCloud 云函数 URL 化接口（云函数内使用 uni-cloud-push，Firebase 在开发者中心托管配置）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UniPushCloudSender {

	private final AppProperties appProperties;
	private final ObjectMapper objectMapper;
	private final RestClient restClient = RestClient.create();

	/**
	 * 向用户全部设备发送系统通知。
	 *
	 * @param data 透传 payload（点击通知后客户端可读）
	 */
	public void sendToDevices(
			Long userId, List<UserPushDevice> devices, String title, String body, Map<String, String> data) {
		String url = appProperties.getMobilePush().getUnipushCloudUrl();
		if (!StringUtils.hasText(url)) {
			log.warn("mobile-push.provider=unipush 但未配置 unipush-cloud-url");
			return;
		}
		List<String> cids = devices.stream()
				.map(UserPushDevice::getPushToken)
				.filter(StringUtils::hasText)
				.distinct()
				.toList();
		if (cids.isEmpty()) {
			return;
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		if (data != null) {
			payload.putAll(data);
		}

		Map<String, Object> bodyJson = new LinkedHashMap<>();
		bodyJson.put("request_id", UUID.randomUUID().toString().replace("-", ""));
		bodyJson.put("cids", cids);
		bodyJson.put("title", title);
		bodyJson.put("content", body);
		bodyJson.put("payload", payload);
		bodyJson.put("force_notification", true);
		bodyJson.put("settings", Map.of("ttl", 86400000));

		String secret = appProperties.getMobilePush().getUnipushHttpSecret();

		try {
			String jsonBody = objectMapper.writeValueAsString(bodyJson);
			ResponseEntity<String> response = restClient
					.post()
					.uri(url.trim())
					.headers(h -> {
						h.setContentType(MediaType.APPLICATION_JSON);
						if (StringUtils.hasText(secret)) {
							h.set("unicloud-secret", secret.trim());
						}
					})
					.body(jsonBody)
					.retrieve()
					.toEntity(String.class);
			handleResponse(userId, devices, response.getStatusCode().value(), response.getBody());
		} catch (Exception e) {
			log.warn("uni-push 云函数调用失败 userId={} cidCount={}", userId, cids.size(), e);
		}
	}

	private void handleResponse(Long userId, List<UserPushDevice> devices, int httpStatus, String responseBody) {
		if (httpStatus < 200 || httpStatus >= 300) {
			log.warn("uni-push 云函数 HTTP {} userId={} body={}", httpStatus, userId, truncate(responseBody));
			return;
		}
		if (!StringUtils.hasText(responseBody)) {
			return;
		}
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			// URL 化可能包一层：{ "code":0, "body": "{...}" } 或直接个推结果
			JsonNode code = root.path("code");
			if (code.isMissingNode()) {
				code = root.path("errCode");
			}
			if (code.isNumber() && code.asInt() != 0) {
				log.warn("uni-push 业务失败 userId={} code={} msg={}", userId, code, root.path("msg").asText(""));
			}
		} catch (Exception e) {
			log.debug("uni-push 响应解析跳过 userId={}", userId);
		}
	}

	private static String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() <= 500 ? s : s.substring(0, 500);
	}
}
