package com.hewei.hzyjy.aimeeting.common.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.common.convention.errorcode.BaseErrorCode;
import com.hewei.hzyjy.aimeeting.common.convention.exception.AbstractException;
import com.hewei.hzyjy.aimeeting.common.convention.result.Result;
import com.hewei.hzyjy.aimeeting.common.convention.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;
import java.util.Optional;

/**
 * Global exception handling for REST controllers.
 */
@Component("globalExceptionHandlerByAdmin")
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(value = {MethodArgumentNotValidException.class, BindException.class})
    public Result validExceptionHandler(HttpServletRequest request, Exception ex) {
        BindingResult bindingResult = ex instanceof MethodArgumentNotValidException methodArgumentNotValidException
                ? methodArgumentNotValidException.getBindingResult()
                : ((BindException) ex).getBindingResult();
        String exceptionStr = extractBindingMessage(bindingResult);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    @ExceptionHandler(value = ConstraintViolationException.class)
    public Result constraintViolationExceptionHandler(HttpServletRequest request, ConstraintViolationException ex) {
        String exceptionStr = ex.getConstraintViolations().stream()
                .findFirst()
                .map(item -> item.getMessage())
                .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public Result httpMessageNotReadableExceptionHandler(HttpServletRequest request, HttpMessageNotReadableException ex) {
        String exceptionStr = "请求体不能为空或格式错误";
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    @ExceptionHandler(value = IllegalArgumentException.class)
    public Result illegalArgumentExceptionHandler(HttpServletRequest request, IllegalArgumentException ex) {
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), ex.getMessage());
    }

    /**
     * 未登录 / 登录失效：HTTP 401 + 明确的业务码。
     * <p>未登录是正常的业务分支，不是系统故障，因此：
     * <ul>
     *     <li>不能落到 {@link #defaultErrorHandler} 被包装成 200 + "系统执行出错"，
     *         否则前端无法区分"该重新登录"和"服务崩了"；</li>
     *     <li>只记 WARN 且不带堆栈，避免任意匿名请求把 ERROR 日志刷满。</li>
     * </ul>
     */
    @ExceptionHandler(value = NotLoginException.class)
    public ResponseEntity<Result<Void>> notLoginExceptionHandler(HttpServletRequest request, NotLoginException ex) {
        log.warn("[{}] {} [unauthorized] loginType={} type={}",
                request.getMethod(), getUrl(request), ex.getLoginType(), ex.getType());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Results.failure(BaseErrorCode.UNAUTHORIZED_ERROR.code(), BaseErrorCode.UNAUTHORIZED_ERROR.message()));
    }

    /**
     * 已登录但角色/权限不足：HTTP 403 + 明确的业务码。
     */
    @ExceptionHandler(value = {NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<Result<Void>> notPermissionExceptionHandler(HttpServletRequest request, SaTokenException ex) {
        log.warn("[{}] {} [forbidden] {}", request.getMethod(), getUrl(request), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Results.failure(BaseErrorCode.FORBIDDEN_ERROR.code(), BaseErrorCode.FORBIDDEN_ERROR.message()));
    }

    @ExceptionHandler(value = {AbstractException.class})
    public Result abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex.toString(), ex.getCause());
            return Results.failure(ex);
        }
        log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex.toString());
        return Results.failure(ex);
    }

    @ExceptionHandler(value = Throwable.class)
    public Result defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        if (Objects.equals(throwable.getClass().getSuperclass().getSimpleName(), AbstractException.class.getSimpleName())) {
            String errorCode = ReflectUtil.getFieldValue(throwable, "errorCode").toString();
            String errorMessage = ReflectUtil.getFieldValue(throwable, "errorMessage").toString();
            return Results.failure(errorCode, errorMessage);
        }
        return Results.failure();
    }

    private String getUrl(HttpServletRequest request) {
        if (StringUtils.isEmpty(request.getQueryString())) {
            return request.getRequestURL().toString();
        }
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }

    private String extractBindingMessage(BindingResult bindingResult) {
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        return Optional.ofNullable(firstFieldError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
    }
}
