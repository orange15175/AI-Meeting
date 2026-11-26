package com.hewei.hzyjy.aimeeting.auth.infrastructure.web;

import com.hewei.hzyjy.aimeeting.auth.application.CurrentUserService;
import com.hewei.hzyjy.aimeeting.auth.domain.CurrentPrincipal;
import com.hewei.hzyjy.aimeeting.common.convention.annotation.CurrentUser;
import com.hewei.hzyjy.aimeeting.common.convention.context.UserContext;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@RequiredArgsConstructor
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUserService currentUserService;

    @Override
    // 【关键节点：登录身份注入】只接管标有 @CurrentUser 的参数；它不是将前端 JSON 反序列化为 UserContext。
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        // 从已登录的身份上下文取得用户信息；缺少有效身份时由该服务拒绝，不能信任请求体自报的 userId。
        CurrentPrincipal principal = currentUserService.requireCurrentPrincipal();
        Class<?> parameterType = parameter.getParameterType();

        if (parameterType.equals(String.class)) {
            return principal.getUsername();
        }
        if (parameterType.equals(Long.class) || parameterType.equals(long.class)) {
            return principal.getUserId();
        }
        // 根据 Controller 参数类型构造 UserContext，随后 Spring 把它作为实参传给接口方法。
        if (parameterType.equals(UserContext.class)) {
            return new UserContext(principal.getUserId(), principal.getUsername());
        }

        throw new ClientException("@CurrentUser only supports String, Long/long and UserContext");
    }
}
