package com.shtbs.interview.common;

public enum ErrorCode {
    SUCCESS(200,"success"),

    PARAM_ERROR(400,"参数错误"),

    USER_ALREADY_EXISTS(4001,"用户已存在"),

    USER_NOT_FOUND(4002,"用户不存在"),

    LOGIN_FAILED(4003, "用户名或密码错误"),

    QUESTION_NOT_FOUND(2001,"题目不存在"),

    SYSTEM_ERROR(500,"系统错误"),

    UNAUTHORIZED(401,"未登录或登录已过期"),

    CODE_EXPIRED(4004,"验证码超时"),

    CODE_ERROR(4005,"验证码错误"),

    EMAIL_SEND_FAILED(4006,"验证码发送失败"),

    INTERVIEW_NOT_FOUND(3001,"面试会话不存在" ),

    INTERVIEW_NOT_IN_PROGRESS(3002,"，面试未开始或已结束" ),

    FORBIDDEN(4007,"拒绝访问" ),
    KNOWLEDGE_BASE_NOT_FOUND(5001,"知识库不存在"),
    KNOWLEDGE_BASE_EXISTS(5008,"知识库名称已存在"),
    DOCUMENT_NOT_FOUND(5002,"文档不存在"),
    DOCUMENT_CONFLICT(5003,"文档内容重复或存在进行中的版本"),
    INVALID_DOCUMENT_FILE(5004,"仅支持不超过10MB的PDF、DOCX、TXT或MD文件，且文件内容须与类型一致"),
    DOCUMENT_NOT_RETRYABLE(5005,"当前文档版本无法重试，请重新上传文件"),
    KNOWLEDGE_BASE_NOT_EMPTY(5006,"知识库仍有文档，请先删除文档"),
    USER_HAS_KNOWLEDGE_BASES(5007,"用户仍有知识库，请先清理知识库" ),
    DOCUMENT_STORAGE_FAILED(5009,"原文件存储失败，请稍后重试" ),
    KNOWLEDGE_CHAT_UNAVAILABLE(5010,"DeepSeek 问答服务暂不可用，请稍后重试" ),
    AGENT_UNAVAILABLE(6001,"Agent 服务暂不可用，请稍后重试"),
    AGENT_LIMIT_EXCEEDED(6002,"Agent 执行超过调用或上下文预算"),
    AGENT_INVALID_OUTPUT(6003,"Agent 返回结果不符合业务约束"),
    CONVERSATION_NOT_FOUND(6004,"会话不存在"),
    REQUEST_CONFLICT(6005,"请求正在处理或回合已变化"),
    USER_HAS_BUSINESS_DATA(6006,"用户仍有业务记录，暂不能删除"),
    PROMPT_NOT_FOUND(6007,"Prompt 版本不存在"),
    AGENT_TIMEOUT(6008,"Agent 执行超时，请稍后重试");


    private final Integer code;

    private final String message;

    ErrorCode(Integer code,String message){
        this.code=code;
        this.message=message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
