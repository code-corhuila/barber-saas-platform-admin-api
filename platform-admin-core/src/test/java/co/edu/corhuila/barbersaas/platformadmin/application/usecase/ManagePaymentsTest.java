package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Unavailable;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases.Checkout;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases.ProviderResult;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PaymentProvider;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagePaymentsTest {

    private static final Instant NOW = Instant.parse("2026-10-27T15:00:00Z");

    private FakeBarbershops barbershops;
    private FakePayments payments;
    private SubscriptionPlan basico;
    private SubscriptionPlan pro;
    private Barbershop shop;
    private ManagePayments service;
    private PlanRepository plans;

    @BeforeEach
    void setUp() {
        barbershops = new FakeBarbershops();
        payments = new FakePayments();
        basico = new SubscriptionPlan(UUID.randomUUID(), "Basico", 4_990_000, 2, null, true, NOW);
        pro = new SubscriptionPlan(UUID.randomUUID(), "Pro", 9_990_000, 6, null, true, NOW);
        Map<UUID, SubscriptionPlan> byId = Map.of(basico.id(), basico, pro.id(), pro);
        shop = barbershops.add("TRIAL", basico.id(), NOW.minus(Duration.ofDays(10)));
        plans = new PlanRepository() {
            public Page<SubscriptionPlan> page(boolean onlyActive, Page.Request page) { throw new AssertionError(); }
            public Optional<SubscriptionPlan> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
            public Optional<Idempotency.Stored> findKey(String key, String operation) { return Optional.empty(); }
            public void saveNew(SubscriptionPlan plan, Idempotency.Key key) { throw new AssertionError(); }
            public void update(SubscriptionPlan plan) { throw new AssertionError(); }
        };
        service = new ManagePayments(payments, plans, barbershops,
                p -> "https://checkout.test/p/?reference=" + p.reference(), UUID::randomUUID,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Caller owner() {
        return new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, shop.id());
    }

    @Test
    void the_owner_sees_plan_trial_and_no_paid_month_yet() {
        var s = service.subscription(owner());
        assertEquals("TRIAL", s.status());
        assertEquals("Basico", s.plan().name());
        assertEquals(shop.trialEndsAt(), s.trialEndsAt());
        assertNull(s.paidUntil());
    }

    @Test
    void starting_a_payment_stores_it_pending_with_the_plan_price_once_per_key() {
        Checkout first = service.start(owner(), pro.id(), "key-pay-0001");
        Checkout retry = service.start(owner(), pro.id(), "key-pay-0001");

        assertEquals(Payment.PENDING, first.payment().status());
        assertEquals(9_990_000, first.payment().amountCents());
        assertEquals(shop.id(), first.payment().barbershopId());
        assertTrue(first.checkoutUrl().contains(first.payment().reference()));
        assertFalse(retry.created());
        assertEquals(first.payment().id(), retry.payment().id());
        assertThrows(IdempotencyKeyReused.class, () -> service.start(owner(), basico.id(), "key-pay-0001"));
    }

    @Test
    void without_a_checkout_no_payment_is_started() {
        ManagePayments noCheckout = new ManagePayments(payments, plans, barbershops, new PaymentProvider() {
            public String checkoutUrl(Payment payment) { throw new AssertionError(); }
            public boolean available() { return false; }
        }, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(Unavailable.class, () -> noCheckout.start(owner(), pro.id(), "key-pay-0009"));
        assertTrue(payments.byId.isEmpty());
        assertTrue(payments.keys.isEmpty());
    }

    @Test
    void a_plan_with_fewer_barbers_than_the_barbershop_has_is_refused() {
        barbershops.rows.put(shop.id(), withBarbers(shop, 4));
        BusinessRuleViolation e = assertThrows(BusinessRuleViolation.class,
                () -> service.start(owner(), basico.id(), "key-pay-0002"));
        assertEquals("This plan allows 2 barbers and the barbershop has 4", e.getMessage());
    }

    @Test
    void an_approved_event_activates_the_paid_plan_for_thirty_days_once() {
        Payment p = service.start(owner(), pro.id(), "key-pay-0003").payment();

        service.apply(new ProviderResult(p.reference(), "tx-1", "APPROVED", p.amountCents()));
        service.apply(new ProviderResult(p.reference(), "tx-1", "APPROVED", p.amountCents()));

        assertEquals("ACTIVE", barbershops.get(shop.id()).status());
        assertEquals(pro.id(), barbershops.get(shop.id()).planId());
        assertEquals(NOW.plus(Duration.ofDays(30)), payments.byId.get(p.id()).periodEnd());
        assertEquals(1, payments.finished);
    }

    @Test
    void a_second_month_starts_when_the_first_one_ends() {
        Payment first = service.start(owner(), pro.id(), "key-pay-0004").payment();
        service.apply(new ProviderResult(first.reference(), "tx-1", "APPROVED", first.amountCents()));
        Payment second = service.start(owner(), pro.id(), "key-pay-0005").payment();
        service.apply(new ProviderResult(second.reference(), "tx-2", "APPROVED", second.amountCents()));

        assertEquals(NOW.plus(Duration.ofDays(60)), service.subscription(owner()).paidUntil());
    }

    @Test
    void a_declined_or_altered_payment_changes_only_the_payment() {
        Payment declined = service.start(owner(), pro.id(), "key-pay-0006").payment();
        Payment altered = service.start(owner(), pro.id(), "key-pay-0007").payment();

        service.apply(new ProviderResult(declined.reference(), "tx-3", "DECLINED", declined.amountCents()));
        service.apply(new ProviderResult(altered.reference(), "tx-4", "APPROVED", 100));

        assertEquals("DECLINED", payments.byId.get(declined.id()).status());
        assertEquals("ERROR", payments.byId.get(altered.id()).status());
        assertEquals("TRIAL", barbershops.get(shop.id()).status());
    }

    @Test
    void an_unknown_reference_changes_nothing() {
        service.apply(new ProviderResult("BS-unknown", "tx-9", "APPROVED", 1));
        assertEquals(0, payments.finished);
    }

    @Test
    void the_owner_lists_its_own_payments_and_the_super_admin_every_one() {
        service.start(owner(), pro.id(), "key-pay-0008");
        Barbershop other = barbershops.add("TRIAL", basico.id(), NOW);
        service.start(new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, other.id()), pro.id(),
                "key-pay-0009");
        Page.Request page = new Page.Request(1, 20);

        assertEquals(1, service.list(owner(), other.id(), page).total());
        assertEquals(2, service.list(new Caller("admin", Role.SUPER_ADMIN, null), null, page).total());
        assertThrows(Forbidden.class, () -> service.list(new Caller("c", Role.CLIENT, null), null, page));
    }

    private static Barbershop withBarbers(Barbershop b, int barbers) {
        return new Barbershop(b.id(), b.name(), b.address(), b.city(), b.latitude(), b.longitude(), b.phone(),
                b.whatsappNumber(), b.logoUrl(), b.status(), b.planId(), b.timezone(), b.cancellationPolicyHours(),
                b.trialEndsAt(), b.createdAt(), b.updatedAt(), barbers);
    }

    private static final class FakePayments implements Payments {
        final Map<UUID, Payment> byId = new HashMap<>();
        final Map<String, Idempotency.Stored> keys = new HashMap<>();
        int finished;

        public Optional<Payment> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        public Optional<Payment> findByReference(String reference) {
            return byId.values().stream().filter(p -> p.reference().equals(reference)).findFirst();
        }

        public Page<Payment> page(UUID barbershopId, Page.Request page) {
            List<Payment> all = new ArrayList<>(byId.values().stream()
                    .filter(p -> barbershopId == null || p.barbershopId().equals(barbershopId)).toList());
            all.sort(Comparator.comparing(Payment::createdAt).reversed());
            return Page.of(all, page);
        }

        public Optional<Instant> paidUntil(UUID barbershopId) {
            return byId.values().stream().filter(p -> p.barbershopId().equals(barbershopId) && p.periodEnd() != null)
                    .map(Payment::periodEnd).max(Comparator.naturalOrder());
        }

        public Optional<Idempotency.Stored> findKey(String key, String operation) {
            return Optional.ofNullable(keys.get(operation + key));
        }

        public void saveNew(Payment payment, Idempotency.Key key) {
            byId.put(payment.id(), payment);
            keys.put(key.operation() + key.key(), new Idempotency.Stored(payment.id(), key.requestHash()));
        }

        public boolean finish(UUID id, String status, String tx, Instant periodEnd, Instant now) {
            Payment p = byId.get(id);
            if (p == null || !p.pending()) {
                return false;
            }
            byId.put(id, new Payment(p.id(), p.barbershopId(), p.planId(), p.planName(), p.reference(),
                    p.amountCents(), p.currency(), status, tx, periodEnd, p.createdAt(), now));
            finished++;
            return true;
        }
    }
}
