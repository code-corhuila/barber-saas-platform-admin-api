package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** platform_admin.payment (ADR-017). */
public interface Payments {

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByReference(String reference);

    /** Newest first; {@code barbershopId} null = every barbershop. */
    Page<Payment> page(UUID barbershopId, Page.Request page);

    /** max(period_end) of the barbershop's APPROVED payments (DEC-PLAT-07). */
    Optional<Instant> paidUntil(UUID barbershopId);

    Optional<Idempotency.Stored> findKey(String key, String operation);

    /** The payment and its idempotency key in ONE transaction (norm 5.3.8). */
    void saveNew(Payment payment, Idempotency.Key key);

    /**
     * Moves a PENDING payment to its final status, once: false when it was no longer PENDING (a
     * repeated event) or when the provider's transaction id is already used.
     */
    boolean finish(UUID id, String status, String providerTransactionId, Instant periodEnd, Instant now);
}
