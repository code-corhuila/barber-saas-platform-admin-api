package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Owners.Owner;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionJobsTest {

    private static final Instant NOW = Instant.parse("2026-10-27T07:00:00Z");
    private final FakeBarbershops shops = new FakeBarbershops();
    private final Map<UUID, Instant> paidUntil = new HashMap<>();
    private final FakeOutbox outbox = new FakeOutbox();
    private final ManagePlansTest.FakePlans plans = new ManagePlansTest.FakePlans();
    private final SubscriptionEvents events = new SubscriptionEvents(outbox,
            id -> List.of(new Owner(UUID.randomUUID(), "Andres Rojas", "andres@example.com")), plans, UUID::randomUUID,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final SubscriptionJobs jobs = new SubscriptionJobs(shops, payments(), events, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Caller worker = new Caller(ExpireTrials.WORKER, Role.SERVICE, null);

    @Test
    void a_paid_month_that_ended_suspends_and_announces_it() {
        Barbershop ended = shops.add("ACTIVE", null, NOW.minus(Duration.ofDays(100)));
        Barbershop paid = shops.add("ACTIVE", null, NOW.minus(Duration.ofDays(100)));
        Barbershop byHand = shops.add("ACTIVE", null, NOW.minus(Duration.ofDays(100)));
        paidUntil.put(ended.id(), NOW.minus(Duration.ofHours(1)));
        paidUntil.put(paid.id(), NOW.plus(Duration.ofDays(10)));

        SubscriptionJobs.Result result = jobs.expire(worker, 50);

        assertEquals(1, result.processed());
        assertEquals("SUSPENDED", shops.get(ended.id()).status());
        assertEquals("ACTIVE", shops.get(paid.id()).status());
        assertEquals("ACTIVE", shops.get(byHand.id()).status());
        OutboxEvent event = outbox.events.get(0);
        assertEquals("BarbershopSuspended", event.type());
        assertEquals("PAYMENT_EXPIRED", event.payload().get("reason"));
        assertEquals("andres@example.com", ((List<?>) event.payload().get("owners")).isEmpty() ? null
                : ((Map<?, ?>) ((List<?>) event.payload().get("owners")).get(0)).get("email"));
    }

    @Test
    void notices_go_once_per_threshold() {
        Barbershop trial = shops.add("TRIAL", null, NOW.minus(Duration.ofDays(54)));     // ends in 6 days
        Barbershop month = shops.add("ACTIVE", null, NOW.minus(Duration.ofDays(90)));
        paidUntil.put(month.id(), NOW.plus(Duration.ofHours(20)));                       // ends tomorrow
        shops.add("TRIAL", null, NOW.minus(Duration.ofDays(10)));                       // 50 days left

        assertEquals(2, jobs.notices(worker, 50).processed());
        assertEquals(0, jobs.notices(worker, 50).processed());
        Map<Object, Object> thresholds = new HashMap<>();
        outbox.events.forEach(e -> thresholds.put(e.payload().get("kind"), e.payload().get("threshold")));
        assertEquals("DAYS_7", thresholds.get("TRIAL"));
        assertEquals("DAYS_1", thresholds.get("PAID"));
        assertTrue(outbox.events.stream().allMatch(e -> e.type().equals("PlanExpiringSoon")));
        assertEquals(trial.id(), outbox.events.stream().filter(e -> "TRIAL".equals(e.payload().get("kind")))
                .findFirst().orElseThrow().barbershopId());
    }

    @Test
    void the_notice_names_the_plan() {
        SubscriptionPlan pro = SubscriptionPlan.create(UUID.randomUUID(), "Pro", 100, 5, null, NOW);
        plans.update(pro);
        Barbershop month = shops.add("ACTIVE", pro.id(), NOW.minus(Duration.ofDays(90)));
        paidUntil.put(month.id(), NOW.plus(Duration.ofHours(20)));
        shops.add("TRIAL", null, NOW.minus(Duration.ofDays(54)));

        jobs.notices(worker, 50);

        Map<Object, Object> names = new HashMap<>();
        outbox.events.forEach(e -> names.put(e.payload().get("kind"), e.payload().get("planName")));
        assertEquals("Pro", names.get("PAID"));
        assertTrue(names.containsKey("TRIAL"));
        assertNull(names.get("TRIAL"));
    }

    @Test
    void the_thresholds_of_the_contract() {
        assertEquals("DAYS_1", SubscriptionJobs.threshold(NOW, NOW.plus(Duration.ofHours(23))));
        assertEquals("DAYS_7", SubscriptionJobs.threshold(NOW, NOW.plus(Duration.ofDays(7))));
        assertNull(SubscriptionJobs.threshold(NOW, NOW.plus(Duration.ofDays(8))));
        assertNull(SubscriptionJobs.threshold(NOW, NOW.minus(Duration.ofMinutes(1))));
    }

    @Test
    void an_expired_trial_also_writes_its_event() {
        shops.add("TRIAL", null, NOW.minus(Duration.ofDays(61)));
        new ExpireTrials(shops, Clock.fixed(NOW, ZoneOffset.UTC), events).expire(worker, 10);
        assertEquals("TRIAL_EXPIRED", outbox.events.get(0).payload().get("reason"));
    }

    @Test
    void only_the_worker_runs_them() {
        Caller admin = new Caller("x", Role.SUPER_ADMIN, null);
        assertThrows(Forbidden.class, () -> jobs.expire(admin, 1));
        assertThrows(Forbidden.class, () -> jobs.notices(admin, 1));
    }

    private Payments payments() {
        return new Payments() {
            public Optional<Payment> findById(UUID id) { return Optional.empty(); }
            public Optional<Payment> findByReference(String reference) { return Optional.empty(); }
            public Page<Payment> page(UUID barbershopId, Page.Request page) { return Page.of(List.of(), page); }
            public Optional<Instant> paidUntil(UUID id) { return Optional.ofNullable(paidUntil.get(id)); }
            public Optional<Idempotency.Stored> findKey(String key, String operation) { return Optional.empty(); }
            public void saveNew(Payment payment, Idempotency.Key key) { }
            public boolean finish(UUID id, String s, String tx, Instant end, Instant now) { return false; }
        };
    }

    private static final class FakeOutbox implements OutboxStore {
        final List<OutboxEvent> events = new ArrayList<>();
        final Set<String> notices = new HashSet<>();

        public void add(OutboxEvent event) { events.add(event); }

        public boolean addNotice(UUID barbershopId, Instant endsAt, String threshold, OutboxEvent event) {
            if (!notices.add(barbershopId + "|" + endsAt + "|" + threshold)) {
                return false;
            }
            events.add(event);
            return true;
        }

        public List<Stored> pending(int limit) { return List.of(); }
        public boolean markPublished(UUID id, Instant now) { return true; }
        public boolean markFailed(UUID id, String reason, Instant now) { return true; }
    }
}
