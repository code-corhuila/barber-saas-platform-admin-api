package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** platform_admin.outbox_event and platform_admin.subscription_notice (DEC-PLAT-07, ADR-016). */
public interface OutboxStore {

    /** A pending row with the correlation id of the request that wrote it. */
    record Stored(OutboxEvent event, String correlationId) { }

    /** Writes one event: the change it reports already happened in barbershop-api. */
    void add(OutboxEvent event);

    /**
     * In ONE transaction: the notice of {@code threshold} for that end date and its event. False, and
     * nothing written, when that notice was already given (pk_subscription_notice).
     */
    boolean addNotice(UUID barbershopId, Instant endsAt, String threshold, OutboxEvent event);

    /** Rows with published_at and failed_at both null, oldest occurred_at first, at most {@code limit}. */
    List<Stored> pending(int limit);

    /** Sets published_at once; false when the id is unknown. */
    boolean markPublished(UUID id, Instant now);

    /** Sets failed_at once and keeps the last reason; false when the id is unknown. */
    boolean markFailed(UUID id, String reason, Instant now);
}
