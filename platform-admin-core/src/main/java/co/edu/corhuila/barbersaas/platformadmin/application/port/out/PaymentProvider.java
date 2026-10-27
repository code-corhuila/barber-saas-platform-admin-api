package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;

/** Where the owner pays (ADR-017: Wompi's Web Checkout in sandbox). */
public interface PaymentProvider {

    /** The page that charges exactly this payment: amount, currency and reference signed. */
    String checkoutUrl(Payment payment);

    /** False when no checkout is configured (DEC-PLAT-08): then no payment is even started. */
    default boolean available() {
        return true;
    }
}
