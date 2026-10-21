package com.shtbs.interview.service;


public interface EmailService {
    void sendVerificationCode(String email,String code);

}
