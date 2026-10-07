package com.talentmatch.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Scripted in-process ChatModel behind the test {@code ResumeExtractionModel} (Phase 4). Records
 * every request; never touches the network. Default: {@link #DEFAULT_JSON} (grounded in
 * {@link TestPdfs#CV_TEXT}) with finish STOP and 1500/600 tokens.
 */
public class FakeExtractionModel implements ChatModel {

    public static final String LABEL = "fake/extractor";

    /** A document whose every value appears in {@link TestPdfs#CV_TEXT}. */
    public static final String DEFAULT_JSON = """
            {"fullName":"Ada Lovelace","email":"ada@example.com","phone":null,"location":"London",
             "headline":"Backend Engineer","summary":null,
             "links":[],
             "experience":[{"title":"Backend Engineer","company":"Acme Ltd","location":null,
               "startDate":"2019-03","endDate":null,"current":true,"technologies":["Java","PostgreSQL"],
               "highlights":["Built payment APIs in Java and PostgreSQL"]}],
             "projects":[],
             "skills":[{"name":"Java","years":null},{"name":"PostgreSQL","years":null},{"name":"Docker","years":null}],
             "certifications":[],
             "education":[{"institution":"University of London","qualification":"BSc","field":"Mathematics",
               "startDate":"2012","endDate":"2015"}],
             "languages":[]}""";

    private final AtomicInteger calls = new AtomicInteger();
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch started = new CountDownLatch(1);
    private volatile Function<ChatRequest, ChatResponse> behaviour = req -> response(DEFAULT_JSON, FinishReason.STOP,
            new TokenUsage(1500, 600));

    // ------------------------------------------------------------------ scripting

    public void reset() {
        releaseAll();
        calls.set(0);
        requests.clear();
        started = new CountDownLatch(1);
        respondJson(DEFAULT_JSON);
    }

    public void respond(Function<ChatRequest, ChatResponse> b) {
        this.behaviour = b;
    }

    public void respondJson(String json) {
        respond(json, FinishReason.STOP, new TokenUsage(1500, 600));
    }

    public void respond(String text, FinishReason finish, TokenUsage usage) {
        respond(req -> response(text, finish, usage));
    }

    public void fail(RuntimeException e) {
        respond(req -> {
            throw e;
        });
    }

    /** Every call blocks until the returned latch is released (max 60s), then returns the default JSON. */
    public CountDownLatch block() {
        CountDownLatch latch = new CountDownLatch(1);
        latches.add(latch);
        respond(req -> {
            try {
                latch.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            return response(DEFAULT_JSON, FinishReason.STOP, new TokenUsage(1500, 600));
        });
        return latch;
    }

    public void releaseAll() {
        latches.forEach(CountDownLatch::countDown);
        latches.clear();
    }

    /** Waits until a call has started (e.g. a blocked call is now RUNNING). */
    public boolean awaitStarted(long seconds) throws InterruptedException {
        return started.await(seconds, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ observations

    public int calls() {
        return calls.get();
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public String lastUserText() {
        return requests.isEmpty() ? null : FakeChatModel.userText(requests.get(requests.size() - 1));
    }

    // ------------------------------------------------------------------ ChatModel

    @Override
    public ChatResponse chat(ChatRequest request) {
        calls.incrementAndGet();
        requests.add(request);
        started.countDown();
        return behaviour.apply(request);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return chat(request);
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }

    public static ChatResponse response(String text, FinishReason finish, TokenUsage usage) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(finish).tokenUsage(usage).build();
    }
}
