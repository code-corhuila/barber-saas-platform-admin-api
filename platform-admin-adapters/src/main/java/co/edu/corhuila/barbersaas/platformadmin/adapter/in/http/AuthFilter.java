package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token on every route under /api/ except the public plan list, and on
 * every route under /internal/ (authentication.md, "Internal operations": never anonymous).
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "auth.caller";

    /** GET /api/v1/plans: the active plans, read during self-registration (FR-004). */
    private static final Pattern PUBLIC = Pattern.compile("^/api/v1/plans/?$");

    /** POST from Wompi: no token, its checksum is checked by the controller (DEC-PLAT-06). */
    private static final String WOMPI_EVENTS = "/api/v1/payments/wompi/events";

    /** The simulated checkout page and its buttons (DEC-PLAT-08): opened from the phone's browser, no token. */
    private static final String SIMULATOR = "/api/v1/payments/simulator/";

    private final Rs256Verifier verifier;
    private final ObjectMapper json;

    public AuthFilter(Rs256Verifier verifier, ObjectMapper json) {
        this.verifier = verifier;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/internal/")) {
            return false;
        }
        if (!path.startsWith("/api/")) {
            return true;
        }
        if ("POST".equals(request.getMethod()) && WOMPI_EVENTS.equals(path)) {
            return true;
        }
        if (path.startsWith(SIMULATOR)) {
            return true;
        }
        return "GET".equals(request.getMethod()) && PUBLIC.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() == 7) {
            reject(response, "a bearer token is required");
            return;
        }
        try {
            request.setAttribute(CALLER_ATTRIBUTE, verifier.verify(header.substring(7), Instant.now()));
        } catch (Rs256Verifier.InvalidTokenException e) {
            reject(response, "the token is invalid or has expired");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(ApiError.UNAUTHORIZED, message));
    }
}
