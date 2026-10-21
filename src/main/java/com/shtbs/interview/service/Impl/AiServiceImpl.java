package com.shtbs.interview.service.Impl;

import com.shtbs.interview.service.AiService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AiServiceImpl implements AiService {
    private final ChatClient chatClient;

    public AiServiceImpl(ChatClient.Builder builder)
    {
        this.chatClient=builder.build();
    }

    public String chat(String message){
        return chatClient.prompt()
                .system("""
                你是一名Java后端面试辅导老师。

                回答要求：
                1. 使用中文回答
                2. 先给出核心结论
                3. 再解释原理
                4. 最后给出面试回答模板
                """)
                .user(message)
                .call()
                .content();
    }
}
