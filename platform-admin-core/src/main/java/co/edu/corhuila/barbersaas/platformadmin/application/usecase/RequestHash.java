package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** The request_hash of an idempotency key: the same key with a different body answers 422. */
final class RequestHash {

    private static final String SEPARATOR = String.valueOf((char) 0);

    private RequestHash() {
    }

    /** Every field of the request is one of the parts: the same key with another body never matches. */
    static String of(Object... parts) {
        String joined = Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining(SEPARATOR));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
