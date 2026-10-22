package com.feng.springailearning.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ConversationIds {

    private ConversationIds() {
    }

    static String resolve(String requestedId, HttpSession session) {
        if (requestedId == null) {
            return "http:" + session.getId();
        }
        if (requestedId.isBlank() || requestedId.length() > 128 || requestedId.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 必须为 1～128 个非空白字符且不能包含控制字符");
        }
        return requestedId;
    }
}
