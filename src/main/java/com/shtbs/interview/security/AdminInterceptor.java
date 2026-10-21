package com.shtbs.interview.security;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;

    public AdminInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method &&
                (method.hasMethodAnnotation(RequireAdmin.class) ||
                        method.getBeanType().isAnnotationPresent(RequireAdmin.class))) {
            Long userId = UserContext.getUserId();
            if (userId == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM app_user WHERE id = ? AND role = 'ADMIN')",
                    Boolean.class, userId))) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        }
        return true;
    }
}
