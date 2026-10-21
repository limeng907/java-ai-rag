package com.shtbs.interview.config;

import com.shtbs.interview.interceptor.LoginInterceptor;
import com.shtbs.interview.security.AdminInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final AdminInterceptor adminInterceptor;

    public WebMvcConfig(
            LoginInterceptor loginInterceptor, AdminInterceptor adminInterceptor) {

        this.loginInterceptor = loginInterceptor;
        this.adminInterceptor = adminInterceptor;
    }

    public void addInterceptors(
            InterceptorRegistry registry) {

        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",
                        "/user/register",
                        "/user/code"
                );
        registry.addInterceptor(adminInterceptor).addPathPatterns("/**");
    }

}
