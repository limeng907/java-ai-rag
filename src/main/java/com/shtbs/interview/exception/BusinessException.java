package com.shtbs.interview.exception;

import com.shtbs.interview.common.ErrorCode;

public class BusinessException extends  RuntimeException {
    private final Integer code;
    public BusinessException(ErrorCode errorCode){
        super(errorCode.getMessage());
        this.code=errorCode.getCode();
    }

    public Integer getCode() {
        return code;
    }
}
