package co.edu.corhuila.barbersaas.platformadmin.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A plan payment through Wompi in sandbox (ADR-017, `payment` of 06-data/models.md §9). It starts
 * PENDING; only Wompi's signed event moves it, once, to one of the final statuses (DEC-PLAT-06).
 */
public record Payment(UUID id, UUID barbershopId, UUID planId, String planName, String reference, long amountCents,
                      String currency, String status, String providerTransactionId, Instant periodEnd,
                      Instant createdAt, Instant updatedAt) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final Set<String> FINAL = Set.of(APPROVED, "DECLINED", "VOIDED", "ERROR");

    public Payment {
        Objects.requireNonNull(id);
        Objects.requireNonNull(barbershopId);
        Objects.requireNonNull(planId);
        Objects.requireNonNull(reference);
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents > 0");
        }
        if (APPROVED.equals(status) != (periodEnd != null)) {
            throw new IllegalArgumentException("periodEnd is set exactly when the payment is APPROVED");
        }
    }

    /** A new payment of {@code plan}, in COP cents (ADR-010). The reference is unique: it is the payment id. */
    public static Payment start(UUID id, UUID barbershopId, SubscriptionPlan plan, Instant now) {
        return new Payment(id, barbershopId, plan.id(), plan.name(), "BS-" + id, plan.priceCents(), "COP", PENDING,
                null, null, now, now);
    }

    public boolean pending() {
        return PENDING.equals(status);
    }
}
