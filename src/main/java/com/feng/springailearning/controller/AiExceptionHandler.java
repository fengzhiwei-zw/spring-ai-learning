package com.feng.springailearning.controller;

import com.openai.errors.OpenAIException;
import com.openai.errors.RateLimitException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.InternalServerException;
import com.feng.springailearning.advisor.LimitedToolCallingAdvisor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiExceptionHandler {

    @ExceptionHandler(OpenAIException.class)
    public ProblemDetail modelFailure(OpenAIException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "模型服务调用失败，请检查模型名称、权限及服务配置");
    }

    @ExceptionHandler({RateLimitException.class, OpenAIIoException.class, InternalServerException.class})
    public ProblemDetail temporaryFailure(OpenAIException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "模型服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(LimitedToolCallingAdvisor.LimitExceededException.class)
    public ProblemDetail toolLimit(LimitedToolCallingAdvisor.LimitExceededException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
    }
}
