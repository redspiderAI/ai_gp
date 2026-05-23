package com.aigp.demo.web.error;

import com.aigp.demo.exception.ConflictException;
import com.aigp.demo.exception.FeatureUnavailableException;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.exception.UnauthorizedException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 将领域异常与校验异常转换为统一的 JSON 错误体，避免栈信息直接暴露给客户端。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	/**
	 * 资源不存在 → 404。
	 */
	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiErrorBody> notFound(NotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiErrorBody("NOT_FOUND", ex.getMessage()));
	}

	/**
	 * 唯一约束或业务冲突 → 409。
	 */
	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiErrorBody> conflict(ConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiErrorBody("CONFLICT", ex.getMessage()));
	}

	/**
	 * 未认证或令牌无效 → 401。
	 */
	@ExceptionHandler(UnauthorizedException.class)
	public ResponseEntity<ApiErrorBody> unauthorized(UnauthorizedException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(new ApiErrorBody("UNAUTHORIZED", ex.getMessage()));
	}

	/**
	 * 功能暂未开放 → 503。
	 */
	@ExceptionHandler(FeatureUnavailableException.class)
	public ResponseEntity<ApiErrorBody> featureDisabled(FeatureUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new ApiErrorBody(ex.getFeatureCode(), ex.getMessage()));
	}

	/**
	 * Bean Validation 失败 → 400，拼接首条校验信息。
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorBody> validation(MethodArgumentNotValidException ex) {
		String msg = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(err -> err.getField() + ": " + err.getDefaultMessage())
				.orElse("请求参数不合法");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiErrorBody("VALIDATION_ERROR", msg));
	}

	/**
	 * Query/Path 类型转换失败（如 date 非 yyyy-MM-dd）→ 400，避免误报 500。
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiErrorBody> typeMismatch(MethodArgumentTypeMismatchException ex) {
		String msg = buildTypeMismatchMessage(ex);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiErrorBody("BAD_REQUEST", msg));
	}

	/**
	 * 缺少必填 Query 参数 → 400。
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiErrorBody> missingParameter(MissingServletRequestParameterException ex) {
		String msg = ex.getParameterName() + " 不能为空";
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiErrorBody("BAD_REQUEST", msg));
	}

	/**
	 * 非法参数 → 400。
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiErrorBody> illegalArgument(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiErrorBody("BAD_REQUEST", ex.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<ApiErrorBody> illegalState(IllegalStateException ex) {
		String msg = ex.getMessage() == null ? "服务暂不可用" : ex.getMessage();
		if (msg.contains("语音服务") || msg.contains("本地语音")) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(new ApiErrorBody("SPEECH_UNAVAILABLE", msg));
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new ApiErrorBody("SERVICE_UNAVAILABLE", msg));
	}

	/**
	 * 未分类异常 → 500（生产环境可改为统一文案并打日志）。
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorBody> fallback(Exception ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ApiErrorBody("INTERNAL_ERROR", ex.getMessage()));
	}

	private static String buildTypeMismatchMessage(MethodArgumentTypeMismatchException ex) {
		String name = ex.getName() != null ? ex.getName() : "参数";
		Class<?> requiredType = ex.getRequiredType();
		if (requiredType != null && LocalDate.class.isAssignableFrom(requiredType)) {
			return name + " 格式须为 yyyy-MM-dd（如 2026-05-22）";
		}
		return name + " 格式不合法";
	}
}
