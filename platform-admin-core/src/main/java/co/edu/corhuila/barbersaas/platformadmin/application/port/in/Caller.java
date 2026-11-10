package co.edu.corhuila.barbersaas.platformadmin.application.port.in;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who calls, taken ONLY from the validated token (authentication.md, "Multi-tenancy"): the
 * subject, the role, the tenant and, for a service token, its scopes. No use case receives the
 * tenant from the path, query or body.
 */
public record Caller(String subject, Role role, UUID barbershopId, Set<String> scopes) {

    /** The four user roles of identity-auth plus SERVICE, for the tokens of worker and workflow. */
    public enum Role { SUPER_ADMIN, ADMIN_BARBERSHOP, BARBER, CLIENT, SERVICE }

    public Caller {
        Objects.requireNonNull(subject);
        Objects.requireNonNull(role);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** A caller whose token carries no scope. */
    public Caller(String subject, Role role, UUID barbershopId) {
        this(subject, role, barbershopId, Set.of());
    }

    /** The tenant every scoped read and write filters by; SUPER_ADMIN and a client without one are refused. */
    public UUID tenant() {
        if (barbershopId == null) {
            throw new Forbidden("This operation requires a barbershop");
        }
        return barbershopId;
    }

    public void require(Role... allowed) {
        if (!Set.of(allowed).contains(role)) {
            throw new Forbidden("The role " + role + " cannot do this");
        }
    }

    /** Internal operations (authentication.md): only that service's token; a user's token is 403. */
    public void requireService(String service) {
        if (role != Role.SERVICE || !subject.equals(service)) {
            throw new Forbidden("Only " + service + " can do this");
        }
    }

    /** As {@link #requireService}, and the token must grant {@code scope} (docs#159). */
    public void requireService(String service, String scope) {
        requireService(service);
        if (!scopes.contains(scope)) {
            throw new Forbidden("The token of " + service + " does not grant " + scope);
        }
    }

    public boolean is(Role r) {
        return role == r;
    }

    /** The user id of the token; a service token has a service name instead. */
    public UUID userId() {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new Forbidden("This operation requires a user");
        }
    }
}
