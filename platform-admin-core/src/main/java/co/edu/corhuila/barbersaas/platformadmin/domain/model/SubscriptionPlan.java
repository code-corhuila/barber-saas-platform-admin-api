package co.edu.corhuila.barbersaas.platformadmin.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A plan a barbershop can have (FR-024, `subscription_plan` of 06-data/models.md §9). The price is in
 * cents of COP (ADR-010); a plan is never deleted, only deactivated, so the barbershops that point at
 * it keep a valid reference.
 */
public record SubscriptionPlan(UUID id, String name, long priceCents, int maxBarbers, String featuresJson,
                               boolean active, Instant createdAt) {

    public static final int NAME_MAX = 50;

    public SubscriptionPlan {
        Objects.requireNonNull(id);
        Objects.requireNonNull(createdAt);
        if (name == null || name.isBlank() || name.length() > NAME_MAX) {
            throw new IllegalArgumentException("name between 1 and " + NAME_MAX + " characters");
        }
        if (priceCents < 0) {
            throw new IllegalArgumentException("priceCents >= 0");
        }
        if (maxBarbers < 1) {
            throw new IllegalArgumentException("maxBarbers >= 1");
        }
    }

    /** A new plan is active (contract: "Created active"). */
    public static SubscriptionPlan create(UUID id, String name, long priceCents, int maxBarbers, String featuresJson,
                                          Instant now) {
        return new SubscriptionPlan(id, name.trim(), priceCents, maxBarbers, featuresJson, true, now);
    }

    /** Price, cap and features change; the new price applies from the next billing period. */
    public SubscriptionPlan edit(String newName, long newPriceCents, int newMaxBarbers, String newFeaturesJson) {
        return new SubscriptionPlan(id, newName.trim(), newPriceCents, newMaxBarbers, newFeaturesJson, active, createdAt);
    }

    public SubscriptionPlan deactivate() {
        return new SubscriptionPlan(id, name, priceCents, maxBarbers, featuresJson, false, createdAt);
    }
}
