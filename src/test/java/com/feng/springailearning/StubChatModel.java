package com.feng.springailearning;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class StubChatModel implements ChatModel {
    public final List<Prompt> requests = new CopyOnWriteArrayList<>();
    public Function<Prompt, ChatResponse> responder = prompt -> reply("测试回答");
    public Function<Prompt, Flux<ChatResponse>> streamer = prompt -> Flux.just(responder.apply(prompt));

    @Override
    public ChatResponse call(Prompt prompt) {
        requests.add(prompt);
        return responder.apply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            requests.add(prompt);
            return streamer.apply(prompt);
        });
    }

    @Override
    public ChatOptions getOptions() {
        return OpenAiChatOptions.builder().model("test-model").build();
    }

    public static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
