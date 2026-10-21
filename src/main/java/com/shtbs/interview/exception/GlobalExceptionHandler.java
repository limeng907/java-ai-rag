package com.shtbs.interview.exception;

import com.shtbs.interview.common.Result;
import com.shtbs.interview.service.Impl.QuestionServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(QuestionServiceImpl.class);
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e){
        log.warn(
                "业务异常，code={}, message={}",
                e.getCode(),
                e.getMessage()
        );
        HttpStatus status = switch (e.getCode()) {
            case 401, 4003 -> HttpStatus.UNAUTHORIZED;
            case 4007 -> HttpStatus.FORBIDDEN;
            case 4002, 2001, 3001, 5001, 5002, 6004, 6007 -> HttpStatus.NOT_FOUND;
            case 4001, 5003, 5005, 5006, 5007, 5008, 6005, 6006 -> HttpStatus.CONFLICT;
            case 6001, 6003 -> HttpStatus.BAD_GATEWAY;
            case 6002 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 6008 -> HttpStatus.GATEWAY_TIMEOUT;
            case 5009 -> HttpStatus.BAD_GATEWAY;
            case 5010 -> HttpStatus.BAD_GATEWAY;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));

    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e){

        log.error("系统发生未知异常", e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统内部错误"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e){
        String message = e.getBindingResult().getFieldError() == null
                ? "参数错误" : e.getBindingResult().getFieldError().getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.error(5004, "文件不能超过10MB"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleBadJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Result.error(400, "请求体格式错误"));
    }

    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<Result<Void>> handleInvalidParameter(Exception e) {
        return ResponseEntity.badRequest().body(Result.error(400,"请求参数格式错误或缺失"));
    }

}
