package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * The owners of a barbershop, read from identity-auth's GET /internal/v1/owners (DEC-AUTH-10), so the
 * plan notices can reach them. An adapter throws ApplicationException.Unavailable when it does not answer.
 */
public interface Owners {

    record Owner(UUID userId, String fullName, String email) { }

    List<Owner> of(UUID barbershopId);
}
