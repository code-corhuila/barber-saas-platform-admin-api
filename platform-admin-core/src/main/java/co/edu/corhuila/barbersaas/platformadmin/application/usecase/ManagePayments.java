package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Unavailable;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PaymentProvider;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ADR-017. The owner starts a payment and pays it in Wompi's checkout; the platform activates the plan
 * only when Wompi's signed event says APPROVED (DEC-PLAT-06). No real money: sandbox keys only.
 */
public class ManagePayments implements PaymentUseCases {

    static final String START_OPERATION = "POST /api/v1/payments";
    /** One paid month, counted from the later of now and the current paidUntil. */
    static final Duration PAID_PERIOD = Duration.ofDays(30);

    private static final Logger LOG = Logger.getLogger(ManagePayments.class.getName());

    private final Payments payments;
    private final PlanRepository plans;
    private final Barbershops barbershops;
    private final PaymentProvider provider;
    private final IdGenerator ids;
    private final Clock clock;

    public ManagePayments(Payments payments, PlanRepository plans, Barbershops barbershops, PaymentProvider provider,
                          IdGenerator ids, Clock clock) {
        this.payments = payments;
        this.plans = plans;
        this.barbershops = barbershops;
        this.provider = provider;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Subscription subscription(Caller caller) {
        caller.require(Role.ADMIN_BARBERSHOP);
        Barbershop b = barbershops.get(caller.tenant());
        SubscriptionPlan plan = b.planId() == null ? null : plans.findById(b.planId()).orElse(null);
        return new Subscription(b.id(), b.status(), plan, b.trialEndsAt(), payments.paidUntil(b.id()).orElse(null));
    }

    @Override
    public Checkout start(Caller caller, UUID planId, String idempotencyKey) {
        caller.require(Role.ADMIN_BARBERSHOP);
        if (!provider.available()) {
            throw new Unavailable("No payment checkout is configured");
        }
        UUID barbershopId = caller.tenant();
        String hash = RequestHash.of(barbershopId, planId);
        Optional<Idempotency.Stored> stored = payments.findKey(idempotencyKey, START_OPERATION);
        if (stored.isPresent()) {
            if (!stored.get().requestHash().equals(hash)) {
                throw new IdempotencyKeyReused();
            }
            Payment same = payments.findById(stored.get().resourceId()).orElseThrow(() -> new NotFound("Payment"));
            return new Checkout(same, provider.checkoutUrl(same), false);
        }
        SubscriptionPlan plan = plans.findById(planId).filter(SubscriptionPlan::active)
                .orElseThrow(() -> new BusinessRuleViolation("The selected plan is not active"));
        Barbershop b = barbershops.get(barbershopId);
        if ("CANCELLED".equals(b.status())) {
            throw new BusinessRuleViolation("A cancelled barbershop cannot pay a plan");
        }
        int barbers = b.activeBarbers() == null ? 0 : b.activeBarbers();
        if (plan.maxBarbers() < barbers) {
            throw new BusinessRuleViolation("This plan allows " + plan.maxBarbers() + " barbers and the barbershop has "
                    + barbers);
        }
        Payment payment = Payment.start(ids.next(), barbershopId, plan, clock.instant());
        payments.saveNew(payment, new Idempotency.Key(idempotencyKey, START_OPERATION, hash));
        return new Checkout(payment, provider.checkoutUrl(payment), true);
    }

    @Override
    public Page<Payment> list(Caller caller, UUID barbershopId, Page.Request page) {
        if (caller.is(Role.ADMIN_BARBERSHOP)) {
            return payments.page(caller.tenant(), page);
        }
        if (caller.is(Role.SUPER_ADMIN)) {
            return payments.page(barbershopId, page);
        }
        throw new Forbidden("Only the owner or the SUPER_ADMIN reads payments");
    }

    @Override
    public void apply(ProviderResult result) {
        Optional<Payment> found = payments.findByReference(result.reference());
        if (found.isEmpty()) {
            LOG.warning("payment event for an unknown reference " + result.reference() + ": ignored");
            return;
        }
        Payment payment = found.get();
        if (!payment.pending() || !Payment.FINAL.contains(result.status())) {
            return;                                     // repeated, or still PENDING at the provider
        }
        String status = result.amountCents() == payment.amountCents() ? result.status() : "ERROR";
        Instant now = clock.instant();
        if (!Payment.APPROVED.equals(status)) {
            payments.finish(payment.id(), status, result.transactionId(), null, now);
            return;
        }
        // The barbershop first: if barbershop-api fails, the payment stays PENDING and Wompi's retry tries again.
        activate(payment);
        Instant from = payments.paidUntil(payment.barbershopId()).filter(now::isBefore).orElse(now);
        payments.finish(payment.id(), status, result.transactionId(), from.plus(PAID_PERIOD), now);
    }

    @Override
    public Payment byReference(String reference) {
        return payments.findByReference(reference).orElseThrow(() -> new NotFound("Payment"));
    }

    /** The paid plan and status ACTIVE, both idempotent: a retried event finds them already set. */
    private void activate(Payment payment) {
        Barbershop b = barbershops.get(payment.barbershopId());
        if (!payment.planId().equals(b.planId())) {
            barbershops.assignPlan(b.id(), payment.planId());
        }
        if (!"ACTIVE".equals(b.status())) {
            try {
                barbershops.changeStatus(b.id(), "ACTIVE");
            } catch (InvalidStatusTransition e) {
                LOG.warning("barbershop " + b.id() + " paid but cannot become ACTIVE from " + b.status());
            }
        }
    }
}
