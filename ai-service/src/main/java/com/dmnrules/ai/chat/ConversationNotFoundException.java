package com.dmnrules.ai.chat;

import java.util.UUID;

public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(UUID id) {
        super("Conversation '" + id + "' not found");
    }
}
