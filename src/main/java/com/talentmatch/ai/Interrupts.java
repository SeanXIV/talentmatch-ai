package com.talentmatch.ai;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Tells a shutdown interrupt apart from a model failure (CV extraction, job-skill enrichment). */
public final class Interrupts {

    private static final int MAX_CAUSE_DEPTH = 16;

    private Interrupts() {
    }

    /**
     * A shutdown interrupted the worker: the flag is set, or an interrupt is in the cause chain. A
     * {@link SocketTimeoutException} is an {@link InterruptedIOException} but a timeout, not an interrupt.
     */
    public static boolean interrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (t instanceof InterruptedException || t instanceof ClosedByInterruptException
                    || (t instanceof InterruptedIOException && !(t instanceof SocketTimeoutException))) {
                return true;
            }
        }
        return false;
    }
}
