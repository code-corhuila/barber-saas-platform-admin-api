package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases.ProviderResult;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The simulated payment gateway (DEC-PLAT-08): while no Wompi key is configured, "Pagar plan" opens
 * this page instead of Wompi's checkout. It says plainly that it is a simulation, and its two buttons
 * hand the same result Wompi's signed event would to the same use case, so the rest of the flow
 * (plan ACTIVE, paid month, history, notices) is the real one. With Wompi keys it answers 404.
 */
@RestController
public class PaymentSimulatorController {

    private final PaymentUseCases payments;
    private final boolean enabled;

    public PaymentSimulatorController(PaymentUseCases payments, PaymentMode mode) {
        this.payments = payments;
        this.enabled = mode.simulated();
    }

    /** Whether "Pagar plan" goes to this simulator: true when no Wompi public key is set. */
    public record PaymentMode(boolean simulated) { }

    @GetMapping(value = "/api/v1/payments/simulator/{reference}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String reference) {
        Payment p = payment(reference);
        String body = p.pending()
                ? """
                  <p class="muted">Plan</p><h2>%s</h2><p class="amount">%s COP</p>
                  <form method="post" action="%s/approve"><button class="ok">Aprobar pago</button></form>
                  <form method="post" action="%s/decline"><button class="no">Rechazar pago</button></form>
                  """.formatted(esc(p.planName()), cop(p.amountCents()), esc(reference), esc(reference))
                : "<h2>Este pago ya fue procesado</h2><p>Estado: " + esc(p.status()) + "</p>" + back();
        return html(body);
    }

    @PostMapping(value = "/api/v1/payments/simulator/{reference}/approve", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> approve(@PathVariable String reference) {
        return finish(reference, "APPROVED", "<h2>Pago aprobado</h2><p>Tu plan quedó activo por 30 días.</p>");
    }

    @PostMapping(value = "/api/v1/payments/simulator/{reference}/decline", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> decline(@PathVariable String reference) {
        return finish(reference, "DECLINED", "<h2>Pago rechazado</h2><p>No se hizo ningún cambio en tu plan.</p>");
    }

    private ResponseEntity<String> finish(String reference, String status, String message) {
        Payment p = payment(reference);
        payments.apply(new ProviderResult(reference, "SIM-" + UUID.randomUUID(), status, p.amountCents()));
        return html(message + back());
    }

    private Payment payment(String reference) {
        if (!enabled) {
            throw new NotFound("Payment simulator");
        }
        return payments.byReference(reference);
    }

    private static String back() {
        return "<p class=\"muted\">Ya puedes cerrar esta página y volver a la app.</p>";
    }

    private static ResponseEntity<String> html(String body) {
        String page = """
                <!doctype html><html lang="es"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>Pasarela de prueba</title>
                <style>body{background:#121212;color:#fff;font-family:system-ui,sans-serif;margin:0;padding:24px;
                text-align:center}.tag{display:inline-block;border:1px solid #ffcc66;color:#ffcc66;border-radius:20px;
                padding:4px 12px;font-size:13px}.muted{color:#888}.amount{color:#d4af37;font-size:28px;font-weight:700}
                button{width:100%%;max-width:22rem;padding:14px;border:0;border-radius:10px;font-size:16px;font-weight:700;
                margin:8px 0}.ok{background:#d4af37;color:#121212}.no{background:#2a1414;color:#ffb3b3}</style></head>
                <body><p class="tag">Pasarela de prueba (simulada) · no se cobra dinero real</p><h1>BarberSaaS</h1>%s</body></html>
                """.formatted(body);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private static String cop(long cents) {
        return "$" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CO")).format(cents / 100);
    }
}
