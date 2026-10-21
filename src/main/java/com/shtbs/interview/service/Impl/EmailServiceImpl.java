package com.shtbs.interview.service.Impl;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.service.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender javaMailSender;

    private static final Logger log =
            LoggerFactory.getLogger(QuestionServiceImpl.class);
    @Value("${spring.mail.username}")
    private String from;

    public EmailServiceImpl(
            JavaMailSender javaMailSender) {

        this.javaMailSender = javaMailSender;
    }

    public void sendVerificationCode(
            String email,
            String code) {

        try{
        SimpleMailMessage message =
                new SimpleMailMessage();

        // 发件人
        message.setFrom(from);

        // 收件人
        message.setTo(email);

        // 标题
        message.setSubject(
                "Ai-Java-Interview 注册验证码"
        );

        // 正文
        message.setText(
                "您的注册验证码是："
                        + code
                        + "\n验证码5分钟内有效，请勿泄露给他人。"
        );

        javaMailSender.send(message);
        log.info("验证码发送成功");
        }
        catch (Exception e){
            log.info("验证码发送失败");
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }
}
