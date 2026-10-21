package com.shtbs.interview.interceptor;


import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public LoginInterceptor(JwtUtil jwtUtil,StringRedisTemplate redisTemplate){
        this.jwtUtil=jwtUtil;
        this.redisTemplate=redisTemplate;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ){
        String authorization=request.getHeader("Authorization");
        if(authorization==null||
                !authorization.startsWith("Bearer ")){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED
            );
        }
        String token=authorization.substring(7);

        try{

            Long userId=jwtUtil.parseUserId(token);

            // 2. Redis key
            String key =
                    "login:user:" + userId;

            // 3. 查询 Redis 中保存的 token
            String redisToken =
                    redisTemplate
                            .opsForValue()
                            .get(key);

            // 4. Redis 中没有登录状态
            if (redisToken == null) {

                response.setStatus(
                        HttpServletResponse.SC_UNAUTHORIZED
                );

                return false;
            }

            // 5. Token 不一致
            if (!redisToken.equals(token)) {

                response.setStatus(
                        HttpServletResponse.SC_UNAUTHORIZED
                );

                return false;
            }


            UserContext.setUserId(userId);

            return true;
        }catch(Exception e){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }
    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ){
        UserContext.remove();
    }

}
