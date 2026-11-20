package com.hewei.hzyjy.aimeeting.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldReturnClientErrorWhenBeanValidationFails() throws Exception {
        mockMvc.perform(post("/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ValidationRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"))
                .andExpect(jsonPath("$.message").value("sessionId不能为空"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void shouldReturnUnauthorizedWhenNotLoggedIn() throws Exception {
        mockMvc.perform(get("/not-login"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A000401"))
                .andExpect(jsonPath("$.message").value("未登录或登录状态已失效"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void shouldReturnForbiddenWhenRoleMissing() throws Exception {
        mockMvc.perform(get("/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A000403"))
                .andExpect(jsonPath("$.message").value("无访问权限"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @RestController
    private static class ValidationController {

        @PostMapping("/validation")
        public String validate(@Valid @RequestBody ValidationRequest request) {
            return request.getSessionId();
        }

        @GetMapping("/not-login")
        public String notLogin() {
            throw new NotLoginException("login", NotLoginException.NOT_TOKEN, NotLoginException.NOT_TOKEN_MESSAGE);
        }

        @GetMapping("/forbidden")
        public String forbidden() {
            throw new NotRoleException("admin");
        }
    }

    private static class ValidationRequest {

        @NotBlank(message = "sessionId不能为空")
        private String sessionId;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
