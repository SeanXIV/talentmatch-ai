package com.talentmatch.ai;

import com.talentmatch.ai.config.ModelInfo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Passive health of the AI layer (health component {@code ai}); never calls the model.
 * UP normally, DEGRADED while the circuit is OPEN. With the configured status order
 * (down, out-of-service, up, degraded, unknown) DEGRADED never fails the overall health, and the
 * readiness group does not include this component. Details never contain keys or URLs.
 */
@Component("aiHealthIndicator")
public class AiHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED");

    private final AiProperties properties;
    private final AiCircuitBreaker circuit;

    public AiHealthIndicator(AiProperties properties, AiCircuitBreaker circuit) {
        this.properties = properties;
        this.circuit = circuit;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("mode", "template-only")
                    .build();
        }
        ModelInfo model = ModelInfo.of(properties);
        AiCircuitBreaker.State state = circuit.state();
        FailureKind lastFailure = circuit.lastFailureKind();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", true);
        details.put("provider", model.provider().id());
        details.put("model", model.modelName());
        details.put("circuit", state.name());
        details.put("consecutiveFailures", circuit.consecutiveFailures());
        details.put("lastSuccessAt", text(circuit.lastSuccessAt()));
        details.put("lastFailureAt", text(circuit.lastFailureAt()));
        details.put("lastFailure", lastFailure == null ? null : lastFailure.name());
        Health.Builder builder = state == AiCircuitBreaker.State.OPEN ? Health.status(DEGRADED) : Health.up();
        return builder.withDetails(details).build();
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
