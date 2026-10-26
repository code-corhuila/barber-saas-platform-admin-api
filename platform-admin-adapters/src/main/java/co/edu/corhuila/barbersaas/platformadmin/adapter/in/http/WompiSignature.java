package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The checksum of a Wompi event (DEC-PLAT-06, https://docs.wompi.co, "Eventos"): the values of the
 * fields named in {@code signature.properties}, read from {@code data}, then {@code timestamp}, then
 * the events secret, concatenated and hashed with SHA-256. Compared in constant time.
 */
public class WompiSignature {

    private final String eventsSecret;

    public WompiSignature(String eventsSecret) {
        this.eventsSecret = eventsSecret == null ? "" : eventsSecret.strip();
    }

    public boolean authentic(JsonNode event) {
        if (eventsSecret.isEmpty() || event == null) {
            return false;
        }
        JsonNode properties = event.path("signature").path("properties");
        String checksum = event.path("signature").path("checksum").asText("");
        if (!properties.isArray() || properties.isEmpty() || checksum.isEmpty() || !event.has("timestamp")) {
            return false;
        }
        StringBuilder chain = new StringBuilder();
        for (JsonNode property : properties) {
            JsonNode value = event.path("data");
            for (String part : property.asText().split("\\.")) {
                value = value.path(part);
            }
            if (value.isMissingNode() || value.isContainerNode()) {
                return false;
            }
            chain.append(value.asText());
        }
        chain.append(event.get("timestamp").asText()).append(eventsSecret);
        return MessageDigest.isEqual(sha256(chain.toString()).getBytes(StandardCharsets.UTF_8),
                checksum.toLowerCase().getBytes(StandardCharsets.UTF_8));
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
