package com.feng.springailearning.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.util.Assert;

/** 为现有工具循环加上硬上限，计数状态仅属于当前请求。 */
public final class LimitedToolCallingAdvisor extends ToolCallingAdvisor {

    private static final String STATE_KEY = LimitedToolCallingAdvisor.class.getName() + ".state";
    private final int maxRounds;
    private final int maxCalls;

    public LimitedToolCallingAdvisor(int maxRounds, int maxCalls) {
        super(ToolCallingManager.builder().build(),
                response -> response != null && response.hasToolCalls(), DEFAULT_ORDER, true);
        Assert.isTrue(maxRounds > 0 && maxCalls > 0, "Tool limits must be positive");
        this.maxRounds = maxRounds;
        this.maxCalls = maxCalls;
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        return initialize(request);
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return initialize(request);
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        return check(response);
    }

    @Override
    protected ChatClientResponse doAfterStream(ChatClientResponse response, StreamAdvisorChain chain) {
        return check(response);
    }

    private ChatClientRequest initialize(ChatClientRequest request) {
        return request.mutate().context(STATE_KEY, new State()).build();
    }

    private ChatClientResponse check(ChatClientResponse response) {
        if (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
            State state = (State) response.context().get(STATE_KEY);
            int calls = response.chatResponse().getResults().stream()
                    .mapToInt(generation -> generation.getOutput().getToolCalls().size()).sum();
            // 在执行工具之前判断，因此超出预算的这一批工具不会执行。
            if (++state.rounds > this.maxRounds || (state.calls += calls) > this.maxCalls) {
                throw new LimitExceededException();
            }
        }
        return response;
    }

    private static final class State {
        private int rounds;
        private int calls;
    }

    public static final class LimitExceededException extends RuntimeException {
        public LimitExceededException() {
            super("工具调用超过本次请求的执行上限");
        }
    }
}
