package com.talentmatch.support;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.TailoringFixtures;
import com.talentmatch.application.tailoring.TailoringPrompts;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Scripted in-process ChatModel behind the test {@code TailoringModel} (Phase 7 §9). Serves both kinds and
 * tells them apart by the user message's first line ({@link TailoringPrompts#CV_TASK} /
 * {@link TailoringPrompts#COVER_LETTER_TASK}). Records every request; never touches the network.
 *
 * <p>Defaults: {@link #CV_JSON} and {@link #LETTER_JSON}, grounded in {@link TailoringFixtures#profile()},
 * finish STOP and 3000/400 tokens.
 */
public class FakeTailoringModel implements ChatModel {

    public static final String LABEL = "fake/tailor";

    /** The context window the test {@code TailoringModel} declares (so CONTEXT_OVERFLOW can be exercised). */
    public static final int CONTEXT_TOKENS = 12288;

    public static final String CV_JSON = """
            {"headline": null, "summary": null, "skills": ["Java", "PostgreSQL"],
             "experience": [{"ref": "E1", "highlights": [{"ref": "E1.H1", "text": "%s"}]},
                            {"ref": "E2", "highlights": [{"ref": "E2.H1", "text": "%s"}]}],
             "projects": [], "certifications": ["C1"]}""".formatted(TailoringFixtures.E1H1, TailoringFixtures.E2H1);

    /** 13 words. */
    public static final String G0 = "I am writing to apply for the Senior Java Developer role at Initech.";
    /** 18 words. */
    public static final String G1 =
            "At Acme Ltd I cut API latency by 40% for 12,000 daily users, working with Java and PostgreSQL.";
    /** 18 words. */
    public static final String G2 = "Before that I spent 4 years at Globex building Python pipelines, and I have used "
            + "Docker since 2018.";

    public static final String LETTER_JSON = letter(G0, G1, G2, G0, G1, G2);

    private final Map<DocumentKind, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();
    private final Map<DocumentKind, Function<ChatRequest, ChatResponse>> behaviour = new ConcurrentHashMap<>();
    private volatile CountDownLatch started = new CountDownLatch(1);

    public FakeTailoringModel() {
        reset();
    }

    /** A letter answer with these paragraphs (JSON-escaped). */
    public static String letter(String... paragraphs) {
        StringBuilder sb = new StringBuilder("{\"paragraphs\": [");
        for (int i = 0; i < paragraphs.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(paragraphs[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("]}").toString();
    }

    // ------------------------------------------------------------------ scripting

    public void reset() {
        releaseAll();
        calls.clear();
        requests.clear();
        started = new CountDownLatch(1);
        respondJson(DocumentKind.CV, CV_JSON);
        respondJson(DocumentKind.COVER_LETTER, LETTER_JSON);
    }

    public void respond(DocumentKind kind, Function<ChatRequest, ChatResponse> b) {
        behaviour.put(kind, b);
    }

    public void respondJson(DocumentKind kind, String json) {
        respond(kind, json, FinishReason.STOP, new TokenUsage(3000, 400));
    }

    public void respond(DocumentKind kind, String text, FinishReason finish, TokenUsage usage) {
        respond(kind, req -> response(text, finish, usage));
    }

    /** Both kinds throw {@code e}. */
    public void fail(RuntimeException e) {
        fail(DocumentKind.CV, e);
        fail(DocumentKind.COVER_LETTER, e);
    }

    public void fail(DocumentKind kind, RuntimeException e) {
        respond(kind, req -> {
            throw e;
        });
    }

    /**
     * Calls of {@code kind} block until the returned latch is released (max 60s), then answer with that
     * kind's default JSON.
     */
    public CountDownLatch block(DocumentKind kind) {
        CountDownLatch latch = new CountDownLatch(1);
        latches.add(latch);
        String json = kind == DocumentKind.CV ? CV_JSON : LETTER_JSON;
        respond(kind, req -> {
            try {
                latch.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            return response(json, FinishReason.STOP, new TokenUsage(3000, 400));
        });
        return latch;
    }

    public void releaseAll() {
        latches.forEach(CountDownLatch::countDown);
        latches.clear();
    }

    /** Waits until a call has started. */
    public boolean awaitStarted(long seconds) throws InterruptedException {
        return started.await(seconds, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ observations

    public int calls() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public int calls(DocumentKind kind) {
        AtomicInteger n = calls.get(kind);
        return n == null ? 0 : n.get();
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    /** The user messages of every call of {@code kind}, in call order. */
    public List<String> userTexts(DocumentKind kind) {
        return requests.stream().filter(r -> kindOf(r) == kind).map(FakeChatModel::userText).toList();
    }

    /** The kind a request asks for, by the user message's first line. */
    public static DocumentKind kindOf(ChatRequest request) {
        String user = FakeChatModel.userText(request);
        String first = user.lines().findFirst().orElse("");
        if (first.equals(TailoringPrompts.CV_TASK)) {
            return DocumentKind.CV;
        }
        if (first.equals(TailoringPrompts.COVER_LETTER_TASK)) {
            return DocumentKind.COVER_LETTER;
        }
        throw new IllegalStateException("not a tailoring prompt; first line: " + first);
    }

    // ------------------------------------------------------------------ ChatModel

    @Override
    public ChatResponse chat(ChatRequest request) {
        DocumentKind kind = kindOf(request);
        calls.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
        requests.add(request);
        started.countDown();
        return behaviour.get(kind).apply(request);
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
