package co.edu.corhuila.barbersaas.platformadmin.adapter.out.wompi;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Unavailable;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PaymentProvider;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;

/**
 * Neither a Wompi key nor PAYMENTS_SIMULATOR=true (DEC-PLAT-08): "Pagar plan" answers 503 and the
 * simulated checkout stays closed, so a host never gives plans away by forgetting a key.
 */
public class NoCheckout implements PaymentProvider {

    @Override
    public String checkoutUrl(Payment payment) {
        throw new Unavailable("No payment checkout is configured");
    }

    @Override
    public boolean available() {
        return false;
    }
}
