package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.chat.ChatMessage;

import java.util.List;

public interface TokenCounter {

    String encoding();

    long countText(String text);

    long countMessages(List<ChatMessage> messages);
}
