package com.feng.springailearning.controller;

import jakarta.validation.Validator;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;
import tools.jackson.core.JacksonException;

final class AiResponses {

    private AiResponses() {
    }

    static String text(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            throw invalid("模型未返回有效响应");
        }
        return text(response.getResult().getOutput().getText());
    }

    static String text(String text) {
        if (!StringUtils.hasText(text)) {
            throw invalid("模型未返回有效文本");
        }
        return text;
    }

    static <T> T structured(Supplier<T> supplier, Validator validator) {
        final T value;
        try {
            value = supplier.get();
        } catch (IllegalArgumentException | JacksonException exception) {
            // 转换异常可能包含完整模型输出，不能直接返回给客户端。
            throw invalid("模型输出无法解析为目标结构");
        }
        if (value == null || !validator.validate(value).isEmpty()) {
            throw invalid("模型输出不符合业务校验规则");
        }
        return value;
    }

    static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, reason);
    }
}
