package com.talentmatch.ai;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Keeps explanations from competing with a CV extraction for the single local model (Ollama on
 * a CPU). A CV extraction holds the gate exclusively for its whole call (20–40 minutes on a slow
 * machine); explanation calls share it and never wait: while an extraction runs they are skipped
 * with {@link ExplanationReason#AI_BUSY}, which does not count as a provider failure (the circuit
 * stays closed and the {@code ai} health component stays UP).
 *
 * <p>Only exists when the provider is Ollama (see {@code OllamaChatModelConfig}); hosted
 * providers serve both kinds of call in parallel. Per JVM (PRODUCTION_READINESS: single instance).
 */
public class LocalModelGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Waits until no explanation call is running, then holds the model exclusively. Call
     * {@link #releaseExclusive()} in a finally block on the same thread.
     *
     * @throws InterruptedException if the thread is interrupted while waiting (shutdown)
     */
    public void acquireExclusive() throws InterruptedException {
        lock.writeLock().lockInterruptibly();
    }

    public void releaseExclusive() {
        lock.writeLock().unlock();
    }

    /**
     * Takes a shared slot for one explanation call without waiting. False while an extraction
     * holds (or is waiting for) the model. Call {@link #releaseShared()} on the same thread.
     */
    public boolean tryAcquireShared() {
        if (lock.hasQueuedThreads()) {
            return false; // an extraction is waiting: let it in instead of starving it
        }
        return lock.readLock().tryLock();
    }

    public void releaseShared() {
        lock.readLock().unlock();
    }

    /** True while a CV extraction holds the model. */
    public boolean exclusiveHeld() {
        return lock.isWriteLocked();
    }
}
