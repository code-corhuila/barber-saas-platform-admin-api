package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hands platform-admin's outbox to barber-saas-worker and records what it delivered (ADR-016). */
public class RelayOutbox {

    /** EventEnvelope of _shared.yaml: what every consumer receives on POST /internal/v1/events. */
    public record EventEnvelope(UUID id, String type, int version, Instant occurredAt, String aggregateType,
                                UUID aggregateId, UUID barbershopId, String correlationId, Map<String, Object> payload) { }

    /** Every platform-admin event is at version 1; it grows only when a payload gains a field. */
    static final int VERSION = 1;

    private final OutboxStore outbox;
    private final Clock clock;

    public RelayOutbox(OutboxStore outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<EventEnvelope> pending(Caller caller, int limit) {
        caller.requireService(ExpireTrials.WORKER);
        return outbox.pending(limit).stream().map(s -> {
            OutboxEvent e = s.event();
            return new EventEnvelope(e.id(), e.type(), VERSION, e.occurredAt(), OutboxEvent.AGGREGATE_TYPE,
                    e.barbershopId(), e.barbershopId(), s.correlationId(), e.payload());
        }).toList();
    }

    public void published(Caller caller, UUID id) {
        caller.requireService(ExpireTrials.WORKER);
        if (!outbox.markPublished(id, clock.instant())) {
            throw new NotFound("Outbox event");
        }
    }

    public void failed(Caller caller, UUID id, String reason) {
        caller.requireService(ExpireTrials.WORKER);
        if (!outbox.markFailed(id, reason, clock.instant())) {
            throw new NotFound("Outbox event");
        }
    }
}
