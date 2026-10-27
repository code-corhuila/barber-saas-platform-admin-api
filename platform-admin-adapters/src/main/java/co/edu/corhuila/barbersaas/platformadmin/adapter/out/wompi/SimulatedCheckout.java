package co.edu.corhuila.barbersaas.platformadmin.adapter.out.wompi;

import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PaymentProvider;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The simulated checkout (DEC-PLAT-08): used when no Wompi key is configured. The URL is relative to
 * the gateway ("/api/v1/payments/simulator/…"); the app prefixes the gateway it already knows, so the
 * same answer works on a laptop, the emulator and the deployed host.
 */
public class SimulatedCheckout implements PaymentProvider {

    public static final String PATH = "/api/v1/payments/simulator/";

    @Override
    public String checkoutUrl(Payment payment) {
        return PATH + URLEncoder.encode(payment.reference(), StandardCharsets.UTF_8);
    }
}
