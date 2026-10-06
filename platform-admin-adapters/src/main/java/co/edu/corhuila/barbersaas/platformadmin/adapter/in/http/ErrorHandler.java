package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Unavailable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** The ONLY place where errors become status codes. Every error answers with the envelope. */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, e.getMessage(), e.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, "the body is not valid JSON"));
    }

    /** A path id that is not a UUID, or a query value of the wrong type. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> mismatch(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR,
                "the request is not valid", java.util.List.of(new ApiError.FieldError(e.getName(), "invalid value"))));
    }

    @ExceptionHandler(Forbidden.class)
    ResponseEntity<ApiError> forbidden(Forbidden e) {
        return respond(HttpStatus.FORBIDDEN, ApiError.of(ApiError.FORBIDDEN, e.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ApiError> notFound(NotFound e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ApiError.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler({BusinessRuleViolation.class, IdempotencyKeyReused.class})
    ResponseEntity<ApiError> businessRule(RuntimeException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.of(ApiError.BUSINESS_RULE_VIOLATION, e.getMessage()));
    }

    @ExceptionHandler(InvalidStatusTransition.class)
    ResponseEntity<ApiError> transition(InvalidStatusTransition e) {
        return respond(HttpStatus.CONFLICT, ApiError.of(ApiError.INVALID_STATUS_TRANSITION, e.getMessage()));
    }

    /** barbershop-api did not answer (DEC-SHOP-06): the cause goes to the log only. */
    @ExceptionHandler(Unavailable.class)
    ResponseEntity<ApiError> unavailable(Unavailable e) {
        log.error("dependency failed: {}", e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE,
                ApiError.of(ApiError.SERVICE_UNAVAILABLE, "the barbershops could not be reached, try again"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ApiError.NOT_FOUND, "no such route"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of(ApiError.NOT_FOUND, "method not allowed on this route"));
    }

    /** Logged in full (the MDC adds the correlation id); the client gets a neutral text. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(ApiError.INTERNAL_ERROR, "unexpected error"));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
