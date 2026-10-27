package co.edu.corhuila.barbersaas.platformadmin.application.port.in;

import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Instant;
import java.util.UUID;

/** The owner's subscription and its payments (ADR-017, HU-SHOP-003, HU-SADMIN-003). */
public interface PaymentUseCases {

    /** Subscription of platform-admin-service.yaml 1.4.0. */
    record Subscription(UUID barbershopId, String status, SubscriptionPlan plan, Instant trialEndsAt,
                        Instant paidUntil) { }

    /** PaymentCheckout: the payment and the page that charges it; {@code created} false on a retried key. */
    record Checkout(Payment payment, String checkoutUrl, boolean created) { }

    /** The provider's result of one transaction, already checked as authentic by the adapter. */
    record ProviderResult(String reference, String transactionId, String status, long amountCents) { }

    /** ADMIN_BARBERSHOP: plan, status, trial and paid month of the token's barbershop. */
    Subscription subscription(Caller caller);

    /** ADMIN_BARBERSHOP: a PENDING payment of the plan and its checkout (DEC-PLAT-05). */
    Checkout start(Caller caller, UUID planId, String idempotencyKey);

    /** ADMIN_BARBERSHOP: its own; SUPER_ADMIN: every one, or of {@code barbershopId}. */
    Page<Payment> list(Caller caller, UUID barbershopId, Page.Request page);

    /** DEC-PLAT-06: applies the provider's result once; a repeated or unknown one changes nothing. */
    void apply(ProviderResult result);

    /** The payment a checkout page shows (the simulated checkout, DEC-PLAT-08); NotFound otherwise. */
    Payment byReference(String reference);
}
