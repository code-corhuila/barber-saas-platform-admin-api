package co.edu.corhuila.barbersaas.platformadmin.adapter.out.wompi;

import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PaymentProvider;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wompi's Web Checkout (ADR-017, https://docs.wompi.co). The URL carries the public key, the amount in
 * cents, the currency, the reference and the integrity signature: SHA-256 of reference + amount +
 * currency + integrity secret, so the amount cannot be changed in the browser. Sandbox keys only
 * (pub_test_…); the secrets come from the environment and are never versioned.
 */
public class WompiCheckout implements PaymentProvider {

    private final String checkoutUrl;
    private final String publicKey;
    private final String integritySecret;
    private final String redirectUrl;

    public WompiCheckout(String checkoutUrl, String publicKey, String integritySecret, String redirectUrl) {
        this.checkoutUrl = checkoutUrl == null || checkoutUrl.isBlank() ? "https://checkout.wompi.co/p/" : checkoutUrl;
        this.publicKey = publicKey == null ? "" : publicKey.strip();
        this.integritySecret = integritySecret == null ? "" : integritySecret.strip();
        this.redirectUrl = redirectUrl == null ? "" : redirectUrl.strip();
    }

    @Override
    public String checkoutUrl(Payment payment) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("public-key", publicKey);
        query.put("currency", payment.currency());
        query.put("amount-in-cents", Long.toString(payment.amountCents()));
        query.put("reference", payment.reference());
        query.put("signature:integrity", integrity(payment));
        if (!redirectUrl.isEmpty()) {
            query.put("redirect-url", redirectUrl);
        }
        return checkoutUrl + "?" + query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    String integrity(Payment payment) {
        return sha256(payment.reference() + payment.amountCents() + payment.currency() + integritySecret);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
