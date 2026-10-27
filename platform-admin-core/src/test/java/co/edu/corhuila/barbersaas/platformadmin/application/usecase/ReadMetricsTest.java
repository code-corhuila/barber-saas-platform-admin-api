package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.MetricsUseCases.Metrics;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-023, DEC-PLAT-09: the counts of every status, this month's barbershops and money. */
class ReadMetricsTest {

    /** 2026-10-19 10:00 in Colombia: the month started on 2026-10-01 at 05:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-10-19T15:00:00Z");
    private static final Instant MONTH_START = Instant.parse("2026-10-01T05:00:00Z");
    private final FakeBarbershops shops = new FakeBarbershops();
    private Instant askedSince;
    private final Payments payments = new Payments() {
        public Revenue revenue(Instant since) {
            askedSince = since;
            return new Revenue(29_970_000, 9_990_000, 1);
        }
        public Optional<Payment> findById(UUID id) { return Optional.empty(); }
        public Optional<Payment> findByReference(String reference) { return Optional.empty(); }
        public Page<Payment> page(UUID barbershopId, Page.Request page) { return Page.of(List.of(), page); }
        public Optional<Instant> paidUntil(UUID id) { return Optional.empty(); }
        public Optional<Idempotency.Stored> findKey(String key, String operation) { return Optional.empty(); }
        public void saveNew(Payment payment, Idempotency.Key key) { }
        public boolean finish(UUID id, String s, String tx, Instant end, Instant now) { return false; }
    };
    private final ReadMetrics metrics = new ReadMetrics(shops, payments, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Caller admin = new Caller("admin", Role.SUPER_ADMIN, null);

    @Test
    void theOperatorSeesEveryStatusThisMonthAndTheMoney() {
        shops.add("TRIAL", null, NOW.minus(Duration.ofDays(55)));                 // trial ends in 5 days
        shops.add("TRIAL", null, MONTH_START.plusSeconds(1));                     // new this month
        shops.add("ACTIVE", null, MONTH_START.minusSeconds(1));                   // last month, by one second
        shops.add("ACTIVE", null, NOW.minus(Duration.ofDays(3)));                 // new this month
        shops.add("SUSPENDED", null, NOW.minus(Duration.ofDays(90)));
        shops.add("CANCELLED", null, NOW.minus(Duration.ofDays(200)));

        Metrics m = metrics.metrics(admin);

        assertEquals(new Metrics(6, 2, 2, 1, 1, 2, 1, 9_990_000, 1, 29_970_000), m);
        assertEquals(MONTH_START, askedSince);
    }

    @Test
    void anEmptyPlatformIsAllZeros() {
        assertEquals(new Metrics(0, 0, 0, 0, 0, 0, 0, 9_990_000, 1, 29_970_000), metrics.metrics(admin));
    }

    @Test
    void onlyTheSuperAdminReadsThem() {
        assertThrows(Forbidden.class, () -> metrics.metrics(new Caller(UUID.randomUUID().toString(),
                Role.ADMIN_BARBERSHOP, UUID.randomUUID())));
    }
}
