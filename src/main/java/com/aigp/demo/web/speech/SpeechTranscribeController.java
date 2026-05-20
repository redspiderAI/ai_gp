package com.aigp.demo.web.speech;

import com.aigp.demo.service.SpeechTranscribeService;
import com.aigp.demo.web.security.CurrentUser;
import com.aigp.demo.web.security.JwtUserClaims;
import com.aigp.demo.web.speech.dto.SpeechTranscribeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音转文字：前端上传录音，后端转发内网 STT，返回文本供对话接口使用。
 */
@RestController
@RequestMapping("/api/v1/speech")
@RequiredArgsConstructor
@Tag(name = "语音转写", description = "上传语音并返回识别文本（需内网 STT 服务）")
@SecurityRequirement(name = "bearerAuth")
public class SpeechTranscribeController {

	private final SpeechTranscribeService speechTranscribeService;

	/**
	 * [语音转写] 上传一段语音，转发至配置的本地 ASR 服务，返回识别文本。
	 */
	@PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(
			summary = "语音转文字",
			description =
					"""
					multipart 字段名必须为 file。成功后将 text 填入 POST /api/v1/ai/chat 的 message。
					Swagger 展开本接口后会出现「浏览器录音测试」面板（需 Authorize 填入 token）。
					""")
	public SpeechTranscribeResponse transcribe(
			@CurrentUser JwtUserClaims user, @RequestPart("file") MultipartFile file) {
		String text = speechTranscribeService.transcribe(user.userId(), file);
		return new SpeechTranscribeResponse(text);
	}
}
