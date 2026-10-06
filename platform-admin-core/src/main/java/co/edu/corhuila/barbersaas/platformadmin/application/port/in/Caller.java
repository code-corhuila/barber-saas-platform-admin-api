package co.edu.corhuila.barbersaas.platformadmin.application.port.in;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who calls, taken ONLY from the validated token (authentication.md, "Multi-tenancy"): the
 * subject, the role and the tenant. No use case receives the tenant from the path, query or body.
 */
public record Caller(String subject, Role role, UUID barbershopId) {

    /** The four user roles of identity-auth plus SERVICE, for the tokens of worker and workflow. */
    public enum Role { SUPER_ADMIN, ADMIN_BARBERSHOP, BARBER, CLIENT, SERVICE }

    public Caller {
        Objects.requireNonNull(subject);
        Objects.requireNonNull(role);
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
