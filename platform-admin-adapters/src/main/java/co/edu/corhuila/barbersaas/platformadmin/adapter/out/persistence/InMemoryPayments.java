package co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Payments in memory, for running the HTTP contract without a database; same rules as the SQL table. */
public class InMemoryPayments implements Payments {

    private final Map<UUID, Payment> rows = new ConcurrentHashMap<>();
    private final Map<String, Idempotency.Stored> keys = new ConcurrentHashMap<>();

    @Override
    public Optional<Payment> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Payment> findByReference(String reference) {
        return rows.values().stream().filter(p -> p.reference().equals(reference)).findFirst();
    }

    @Override
    public Page<Payment> page(UUID barbershopId, Page.Request page) {
        return Page.of(rows.values().stream()
                .filter(p -> barbershopId == null || p.barbershopId().equals(barbershopId))
                .sorted(Comparator.comparing(Payment::createdAt).reversed())
                .toList(), page);
    }

    @Override
    public Optional<Instant> paidUntil(UUID barbershopId) {
        return rows.values().stream()
                .filter(p -> p.barbershopId().equals(barbershopId) && Payment.APPROVED.equals(p.status()))
                .map(Payment::periodEnd).max(Comparator.naturalOrder());
    }

    @Override
    public Revenue revenue(Instant since) {
        List<Payment> approved = rows.values().stream().filter(p -> Payment.APPROVED.equals(p.status())).toList();
        List<Payment> recent = approved.stream().filter(p -> !p.updatedAt().isBefore(since)).toList();
        return new Revenue(approved.stream().mapToLong(Payment::amountCents).sum(),
                recent.stream().mapToLong(Payment::amountCents).sum(), recent.size());
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return Optional.ofNullable(keys.get(operation + " " + key));
    }

    @Override
    public synchronized void saveNew(Payment payment, Idempotency.Key key) {
        rows.put(payment.id(), payment);
        keys.put(key.operation() + " " + key.key(), new Idempotency.Stored(payment.id(), key.requestHash()));
    }

    @Override
    public synchronized boolean finish(UUID id, String status, String providerTransactionId, Instant periodEnd,
                                       Instant now) {
        Payment p = rows.get(id);
        boolean usedTransaction = providerTransactionId != null && rows.values().stream()
                .anyMatch(other -> providerTransactionId.equals(other.providerTransactionId()));
        if (p == null || !p.pending() || usedTransaction) {
            return false;
        }
        rows.put(id, new Payment(p.id(), p.barbershopId(), p.planId(), p.planName(), p.reference(), p.amountCents(),
                p.currency(), status, providerTransactionId, periodEnd, p.createdAt(), now));
        return true;
    }
}
