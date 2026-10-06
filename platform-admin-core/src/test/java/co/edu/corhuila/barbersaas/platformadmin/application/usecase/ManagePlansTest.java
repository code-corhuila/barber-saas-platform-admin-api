package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidField;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases.Created;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases.PlanData;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagePlansTest {

    private final FakePlans repo = new FakePlans();
    private final ManagePlans plans = new ManagePlans(repo, new FakeBarbershops(), UUID::randomUUID,
            Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC));
    private final Caller admin = new Caller(UUID.randomUUID().toString(), Role.SUPER_ADMIN, null);
    private final Page.Request first = new Page.Request(1, 20);

    private static PlanData data(String name, long cents) {
        return new PlanData(name, cents, 3, null);
    }

    @Test
    void a_new_plan_is_active_and_a_retry_with_the_same_key_returns_it() {
        Created<SubscriptionPlan> created = plans.create(admin, data("Pro", 9_990_000), "key-00000001");
        Created<SubscriptionPlan> again = plans.create(admin, data("Pro", 9_990_000), "key-00000001");
        assertTrue(created.created());
        assertTrue(created.value().active());
        assertFalse(again.created());
        assertEquals(created.value().id(), again.value().id());
        assertEquals(1, plans.list(admin, first).total());
    }

    @Test
    void the_same_key_with_another_body_is_refused() {
        plans.create(admin, data("Pro", 9_990_000), "key-00000001");
        assertThrows(IdempotencyKeyReused.class, () -> plans.create(admin, data("Pro", 1), "key-00000001"));
    }

    @Test
    void two_plans_cannot_share_a_name() {
        plans.create(admin, data("Pro", 9_990_000), "key-00000001");
        InvalidField e = assertThrows(InvalidField.class, () -> plans.create(admin, data("Pro", 1), "key-00000002"));
        assertEquals("name", e.field());
    }

    @Test
    void the_public_list_shows_active_plans_only_cheapest_first() {
        SubscriptionPlan premium = plans.create(admin, data("Premium", 17_990_000), "key-00000001").value();
        plans.create(admin, data("Basico", 4_990_000), "key-00000002");
        repo.update(premium.deactivate());
        assertEquals(1, plans.listActive(first).total());
        assertEquals("Basico", plans.list(admin, first).items().get(0).name());
    }

    @Test
    void an_edit_keeps_the_plan_active_and_its_creation_date() {
        SubscriptionPlan pro = plans.create(admin, data("Pro", 9_990_000), "key-00000001").value();
        SubscriptionPlan edited = plans.update(admin, pro.id(), new PlanData("Pro+", 10_990_000, 8, "{\"a\":1}"));
        assertEquals("Pro+", edited.name());
        assertEquals(pro.createdAt(), edited.createdAt());
        assertTrue(edited.active());
    }

    @Test
    void only_super_admin_manages_plans_and_an_unknown_plan_is_404() {
        Caller owner = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, UUID.randomUUID());
        assertThrows(Forbidden.class, () -> plans.list(owner, first));
        assertThrows(Forbidden.class, () -> plans.create(owner, data("Pro", 1), "key-00000001"));
        assertThrows(NotFound.class, () -> plans.get(admin, UUID.randomUUID()));
    }

    /** The table's rules in memory: the unique name and the key next to the plan. */
    static final class FakePlans implements PlanRepository {
        private final Map<UUID, SubscriptionPlan> rows = new HashMap<>();
        private final Map<String, Idempotency.Stored> keys = new HashMap<>();

        @Override
        public Page<SubscriptionPlan> page(boolean onlyActive, Page.Request page) {
            return Page.of(rows.values().stream().filter(p -> !onlyActive || p.active())
                    .sorted(Comparator.comparingLong(SubscriptionPlan::priceCents)).toList(), page);
        }

        @Override
        public Optional<SubscriptionPlan> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Idempotency.Stored> findKey(String key, String operation) {
            return Optional.ofNullable(keys.get(operation + key));
        }

        @Override
        public void saveNew(SubscriptionPlan plan, Idempotency.Key key) {
            update(plan);
            keys.put(key.operation() + key.key(), new Idempotency.Stored(plan.id(), key.requestHash()));
        }

        @Override
        public void update(SubscriptionPlan plan) {
            if (rows.values().stream().anyMatch(p -> !p.id().equals(plan.id()) && p.name().equals(plan.name()))) {
                throw new NameTaken();
            }
            rows.put(plan.id(), plan);
        }
    }
}
