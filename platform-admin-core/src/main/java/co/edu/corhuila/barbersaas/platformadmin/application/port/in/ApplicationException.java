package co.edu.corhuila.barbersaas.platformadmin.application.port.in;

/** Errors of the use cases. The HTTP adapter turns each into one status code. */
public abstract class ApplicationException extends RuntimeException {

    protected ApplicationException(String message) {
        super(message);
    }

    /** 404: the plan or the barbershop does not exist. */
    public static class NotFound extends ApplicationException {
        public NotFound(String what) {
            super(what + " not found");
        }
    }

    /** 403: only SUPER_ADMIN operates the platform (FR-025), only the worker calls the internal jobs. */
    public static class Forbidden extends ApplicationException {
        public Forbidden(String message) {
            super(message);
        }
    }

    /** 422: a business rule refuses the change, e.g. an inactive plan or a plan still in use (DEC-PLAT-02). */
    public static class BusinessRuleViolation extends ApplicationException {
        public BusinessRuleViolation(String message) {
            super(message);
        }
    }

    /** 422: the same Idempotency-Key with a different body. */
    public static class IdempotencyKeyReused extends ApplicationException {
        public IdempotencyKeyReused() {
            super("The Idempotency-Key was already used with a different request");
        }
    }

    /** 409: the barbershop lifecycle does not allow that move; barbershop-api decides it. */
    public static class InvalidStatusTransition extends ApplicationException {
        public InvalidStatusTransition(String message) {
            super(message);
        }
    }

    /** 503: barbershop-api did not answer; the cause goes to the log, never to the client. */
    public static class Unavailable extends ApplicationException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
