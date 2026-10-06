package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** barbershop-api's internal operations in memory, with its lifecycle (entities-and-rules.md). */
final class FakeBarbershops implements Barbershops {

    private static final Map<String, Set<String>> MOVES = Map.of(
            "TRIAL", Set.of("ACTIVE", "SUSPENDED"),
            "ACTIVE", Set.of("SUSPENDED", "CANCELLED"),
            "SUSPENDED", Set.of("ACTIVE", "CANCELLED"),
            "CANCELLED", Set.of());

    final Map<UUID, Barbershop> rows = new LinkedHashMap<>();
    private final Map<String, UUID> keys = new LinkedHashMap<>();

    Barbershop add(String status, UUID planId, Instant createdAt) {
        Barbershop b = new Barbershop(UUID.randomUUID(), "Barbería " + rows.size(), null, "Neiva", null, null, "300",
                null, null, status, planId, "America/Bogota", 2, createdAt.plus(Duration.ofDays(60)), createdAt,
                createdAt);
        rows.put(b.id(), b);
        return b;
    }

    @Override
    public Page<Barbershop> list(String status, UUID planId, Instant trialEndsBefore, Page.Request page) {
        return Page.of(rows.values().stream()
                .filter(b -> status == null || b.status().equals(status))
                .filter(b -> planId == null || planId.equals(b.planId()))
                .filter(b -> trialEndsBefore == null || b.trialEndsAt().isBefore(trialEndsBefore))
                .sorted(Comparator.comparing(Barbershop::createdAt).reversed())
                .toList(), page);
    }

    @Override
    public Barbershop get(UUID id) {
        Barbershop b = rows.get(id);
        if (b == null) {
            throw new NotFound("Barbershop");
        }
        return b;
    }

    @Override
    public Barbershop changeStatus(UUID id, String status) {
        Barbershop b = get(id);
        if (b.status().equals(status)) {
            return b;
        }
        if (!MOVES.get(b.status()).contains(status)) {
            throw new InvalidStatusTransition(b.status() + " cannot move to " + status);
        }
        return put(new Barbershop(b.id(), b.name(), b.address(), b.city(), b.latitude(), b.longitude(), b.phone(),
                b.whatsappNumber(), b.logoUrl(), status, b.planId(), b.timezone(), b.cancellationPolicyHours(),
                b.trialEndsAt(), b.createdAt(), b.updatedAt()));
    }

    @Override
    public Barbershop assignPlan(UUID id, UUID planId) {
        Barbershop b = get(id);
        if (b.status().equals("CANCELLED")) {
            throw new InvalidStatusTransition("a cancelled barbershop keeps its plan");
        }
        return put(new Barbershop(b.id(), b.name(), b.address(), b.city(), b.latitude(), b.longitude(), b.phone(),
                b.whatsappNumber(), b.logoUrl(), b.status(), planId, b.timezone(), b.cancellationPolicyHours(),
                b.trialEndsAt(), b.createdAt(), b.updatedAt()));
    }

    @Override
    public Created create(NewBarbershop n, String idempotencyKey) {
        UUID known = keys.get(idempotencyKey);
        if (known != null) {
            return new Created(get(known), false);
        }
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        Barbershop b = put(new Barbershop(UUID.randomUUID(), n.name(), n.address(), n.city(), n.latitude(),
                n.longitude(), Objects.requireNonNull(n.phone()), null, null, "TRIAL", null, "America/Bogota", 2,
                now.plus(Duration.ofDays(60)), now, now));
        keys.put(idempotencyKey, b.id());
        return new Created(b, true);
    }

    private Barbershop put(Barbershop b) {
        rows.put(b.id(), b);
        return b;
    }
}
