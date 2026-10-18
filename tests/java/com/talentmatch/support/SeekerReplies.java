package com.talentmatch.support;

import com.talentmatch.ai.SeekerExplanationPrompts;
import dev.langchain4j.model.output.FinishReason;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 6: a {@link FakeChatModel} responder that answers seeker prompts with grounded second-person
 * JSON (naming the first matched required skill) and recruiter prompts with the fake's default JSON.
 */
public final class SeekerReplies {

    private static final Pattern MATCHED_REQ = Pattern.compile("(?m)^Matched required skills: (.*)$");

    private SeekerReplies() {
    }

    /** Installs the responder; {@code explanationForSkill} writes the free text for the matched skill. */
    public static void install(FakeChatModel fake, Function<String, String> explanationForSkill) {
        fake.respond(req -> {
            String prompt = FakeChatModel.userText(req);
            if (!prompt.startsWith(SeekerExplanationPrompts.INTRO)) {
                return FakeChatModel.response(fake.defaultJson(req), FinishReason.STOP);
            }
            return FakeChatModel.response(json(prompt, explanationForSkill), FinishReason.STOP);
        });
    }

    /** Installs the responder with {@link #grounded(String)}. */
    public static void install(FakeChatModel fake) {
        install(fake, SeekerReplies::grounded);
    }

    /**
     * Like {@link #install(FakeChatModel)}, but every call first waits (up to 30s) for the returned
     * latch. {@link FakeChatModel#block()} answers with the default JSON, whose "#n" the seeker
     * grounding check rejects as an invented number, so seeker tests that block need this one.
     * Count the latch down in a finally block.
     */
    public static java.util.concurrent.CountDownLatch installBlocking(FakeChatModel fake) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        fake.respond(req -> {
            try {
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String prompt = FakeChatModel.userText(req);
            if (!prompt.startsWith(SeekerExplanationPrompts.INTRO)) {
                return FakeChatModel.response(fake.defaultJson(req), FinishReason.STOP);
            }
            return FakeChatModel.response(json(prompt, SeekerReplies::grounded), FinishReason.STOP);
        });
        return latch;
    }

    public static String grounded(String skill) {
        return "You bring " + skill + ", which this role requires.";
    }

    /** Number of seeker prompts the fake has seen. */
    public static long seekerPrompts(FakeChatModel fake) {
        return fake.prompts().stream().filter(p -> p.startsWith(SeekerExplanationPrompts.INTRO)).count();
    }

    static String json(String prompt, Function<String, String> explanationForSkill) {
        Matcher m = MATCHED_REQ.matcher(prompt);
        String skill = null;
        if (m.find() && !m.group(1).startsWith("none")) {
            skill = m.group(1).split(",")[0].replaceAll("\\s*\\(.*$", "").trim();
        }
        if (skill == null) {
            return "{\"headline\":\"A stretch role for you\",\"explanation\":\"This role asks for skills you are still "
                    + "building toward, so it is a stretch for now.\",\"strengths\":[],\"gaps\":[]}";
        }
        return "{\"headline\":\"A good fit for you\",\"explanation\":\"" + explanationForSkill.apply(skill)
                + "\",\"strengths\":[\"" + skill + "\"],\"gaps\":[]}";
    }
}
