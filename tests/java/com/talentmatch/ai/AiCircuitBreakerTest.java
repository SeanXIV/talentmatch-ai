package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.ai.AiCircuitBreaker.State;
import com.talentmatch.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Spec §2 AiCircuitBreaker / §10 unit 6 (threshold 3, open 30s, call timeout 60s). */
class AiCircuitBreakerTest {

    private final MutableClock clock = MutableClock.fixedAt(AiFixtures.T0);
    private final AiCircuitBreaker circuit = new AiCircuitBreaker(AiFixtures.props(), clock);

    private void fail(int n) {
        for (int i = 0; i < n; i++) {
            circuit.recordFailure(FailureKind.TIMEOUT);
        }
    }

    @Test
    void opensAtThresholdAndRejectsWhileOpen() {
        assertThat(circuit.state()).isEqualTo(State.CLOSED);
        assertThat(circuit.lastFailureAt()).isNull();
        fail(2);
        assertThat(circuit.state()).isEqualTo(State.CLOSED);
        assertThat(circuit.allowRequest()).isTrue();
        fail(1);
        assertThat(circuit.state()).isEqualTo(State.OPEN);
        assertThat(circuit.consecutiveFailures()).isEqualTo(3);
        assertThat(circuit.lastFailureKind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(circuit.lastFailureAt()).isEqualTo(AiFixtures.T0);
        assertThat(circuit.allowRequest()).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(circuit.allowRequest()).isFalse();
        assertThat(circuit.state()).isEqualTo(State.OPEN);
    }

    @Test
    void successResetsTheConsecutiveCount() {
        fail(2);
        circuit.recordSuccess();
        assertThat(circuit.lastSuccessAt()).isEqualTo(AiFixtures.T0);
        fail(2);
        assertThat(circuit.state()).isEqualTo(State.CLOSED);
        circuit.recordFailure();
        assertThat(circuit.state()).isEqualTo(State.OPEN);
        assertThat(circuit.lastFailureKind()).isEqualTo(FailureKind.PROVIDER_ERROR);
    }

    @Test
    void halfOpenAllowsExactlyOneProbeAndSuccessCloses() {
        fail(3);
        clock.advance(Duration.ofSeconds(30));
        assertThat(circuit.state()).as("reported without consuming the probe").isEqualTo(State.HALF_OPEN);
        assertThat(circuit.allowRequest()).isTrue();
        assertThat(circuit.allowRequest()).isFalse();
        assertThat(circuit.allowRequest()).isFalse();
        circuit.recordSuccess();
        assertThat(circuit.state()).isEqualTo(State.CLOSED);
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(circuit.allowRequest()).isTrue();
        assertThat(circuit.allowRequest()).isTrue();
    }

    @Test
    void probeFailureReopens() {
        fail(3);
        clock.advance(Duration.ofSeconds(31));
        assertThat(circuit.allowRequest()).isTrue();
        circuit.recordFailure(FailureKind.PROVIDER_ERROR);
        assertThat(circuit.state()).isEqualTo(State.OPEN);
        assertThat(circuit.allowRequest()).isFalse();
        clock.advance(Duration.ofSeconds(30));
        assertThat(circuit.allowRequest()).as("next probe after another open period").isTrue();
    }

    @Test
    void lostProbeExpiresAfterCallTimeoutPlusOpenDuration() {
        fail(3);
        clock.advance(Duration.ofSeconds(30));
        assertThat(circuit.allowRequest()).isTrue();
        clock.advance(Duration.ofSeconds(89));
        assertThat(circuit.allowRequest()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(circuit.allowRequest()).isTrue();
    }
}
