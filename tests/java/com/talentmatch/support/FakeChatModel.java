package com.talentmatch.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted in-process ChatModel for AI integration tests (spec §10). Overrides {@code chat(ChatRequest)}
 * directly, records every request and counts calls. Never touches the network.
 *
 * <p>Default behaviour: a valid, grounded JSON explanation ("AI text #n ...") with finish reason STOP.
 */
public class FakeChatModel implements ChatModel {

    private static final Pattern CANDIDATE = Pattern.compile("(?m)^Candidate: (.*)$");
    private static final Pattern MATCHED_REQ = Pattern.compile("(?m)^Matched required skills: (.*)$");

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger serial = new AtomicInteger();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();
    private volatile Function<ChatRequest, ChatResponse> behaviour = this::defaultReply;

    // ------------------------------------------------------------------ scripting

    public void reset() {
        releaseAll();
        calls.set(0);
        prompts.clear();
        behaviour = this::defaultReply;
    }

    public void respond(Function<ChatRequest, ChatResponse> b) {
        this.behaviour = b;
    }

    /** Valid default JSON after {@code delay}. */
    public void delayDefault(Duration delay) {
        respond(req -> {
            sleep(delay);
            return defaultReply(req);
        });
    }

    /** Raw assistant text (e.g. not JSON) with the given finish reason. */
    public void respondText(String text, FinishReason finish) {
        respond(req -> response(text, finish));
    }

    /** Default JSON but with this finish reason. */
    public void respondFinish(FinishReason finish) {
        respond(req -> response(defaultJson(req), finish));
    }

    public void fail(RuntimeException e) {
        respond(req -> {
            throw e;
        });
    }

    /** Every call blocks until the returned latch is released (or 30s), then answers with the default. */
    public CountDownLatch block() {
        CountDownLatch latch = new CountDownLatch(1);
        latches.add(latch);
        respond(req -> {
            await(latch);
            return defaultReply(req);
        });
        return latch;
    }

    public void releaseAll() {
        latches.forEach(CountDownLatch::countDown);
        latches.clear();
    }

    // ------------------------------------------------------------------ observations

    public int calls() {
        return calls.get();
    }

    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    /** Candidate names in the order they were asked about. */
    public List<String> candidatesAsked() {
        List<String> out = new ArrayList<>();
        for (String p : prompts) {
            Matcher m = CANDIDATE.matcher(p);
            out.add(m.find() ? m.group(1) : "?");
        }
        return out;
    }

    // ------------------------------------------------------------------ ChatModel

    @Override
    public ChatResponse chat(ChatRequest request) {
        calls.incrementAndGet();
        prompts.add(userText(request));
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

    // ------------------------------------------------------------------ helpers

    public static ChatResponse response(String text, FinishReason finish) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(finish)
                .tokenUsage(new TokenUsage(100, 40)).build();
    }

    public static String userText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage u) {
                return u.singleText();
            }
        }
        return "";
    }

    private ChatResponse defaultReply(ChatRequest req) {
        return response(defaultJson(req), FinishReason.STOP);
    }

    /** Grounded JSON: the first matched required skill (if any) as a strength. */
    public String defaultJson(ChatRequest req) {
        String prompt = userText(req);
        int n = serial.incrementAndGet();
        Matcher m = MATCHED_REQ.matcher(prompt);
        String strength = null;
        if (m.find() && !m.group(1).startsWith("none")) {
            strength = m.group(1).split(",")[0].replaceAll("\\s*\\(.*$", "").trim();
        }
        String strengths = strength == null ? "[]" : "[\"" + strength + " experience\"]";
        return "{\"headline\":\"AI headline " + n + "\",\"explanation\":\"AI text #" + n
                + ": the candidate covers the key required skills listed for this role.\",\"strengths\":"
                + strengths + ",\"gaps\":[]}";
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
