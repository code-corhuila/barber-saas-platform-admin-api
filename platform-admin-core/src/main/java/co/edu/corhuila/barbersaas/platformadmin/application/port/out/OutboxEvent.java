package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A row of platform_admin.outbox_event, relayed later by barber-saas-worker (norm 5.3.11, ADR-016).
 * The aggregate is the barbershop the event is about; the adapter adds the request's correlation id.
 */
public record OutboxEvent(UUID id, UUID barbershopId, String type, Map<String, Object> payload, Instant occurredAt) {

    public static final String AGGREGATE_TYPE = "barbershop";

    public OutboxEvent {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
