package com.kalo.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /*
     * =========================================================
     * 409 CONFLICT
     * =========================================================
     */

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflictException(
            ConflictException exception,
            HttpServletRequest request
    ) {

        return build(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {

        /*
         * Database-level invariants (unique/partial indexes) are the last line
         * of defence behind the service checks. The driver message is logged
         * but never returned, to avoid leaking schema details.
         */
        log.warn(
                "Database constraint violated on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.CONFLICT,
                "The request conflicts with the current state of the resource",
                request
        );
    }

    /*
     * =========================================================
     * 400 BAD REQUEST
     * =========================================================
     */

    @ExceptionHandler(InvalidOperationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOperation(
            InvalidOperationException exception,
            HttpServletRequest request
    ) {

        return build(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {

        String message = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return build(
                HttpStatus.BAD_REQUEST,
                message.isBlank()
                        ? "Request validation failed"
                        : message,
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {

        String message = exception
                .getConstraintViolations()
                .stream()
                .map(violation ->
                        violation.getPropertyPath()
                                + ": "
                                + violation.getMessage()
                )
                .collect(Collectors.joining(", "));

        return build(
                HttpStatus.BAD_REQUEST,
                message.isBlank()
                        ? "Request validation failed"
                        : message,
                request
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request
    ) {

        log.debug(
                "Malformed request on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.BAD_REQUEST,
                "Request body or parameters are malformed",
                request
        );
    }

    /*
     * =========================================================
     * 401 UNAUTHORIZED
     * =========================================================
     */

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(
            UnauthorizedException exception,
            HttpServletRequest request
    ) {

        /*
         * The message is echoed here, unlike the two below: these are
         * raised by our own code for a caller who is already known, so
         * saying "your session expired" reveals nothing and saying
         * "authentication failed" would send them hunting for a password
         * problem they do not have.
         */
        return build(
                HttpStatus.UNAUTHORIZED,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(
            BadCredentialsException exception,
            HttpServletRequest request
    ) {

        return build(
                HttpStatus.UNAUTHORIZED,
                "Invalid phone or password",
                request
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException exception,
            HttpServletRequest request
    ) {

        /*
         * Covers disabled/locked accounts as well. The reason is deliberately
         * not echoed back, so the API does not confirm which accounts exist or
         * what state they are in.
         */
        return build(
                HttpStatus.UNAUTHORIZED,
                "Authentication failed",
                request
        );
    }

    /*
     * =========================================================
     * 403 FORBIDDEN
     * =========================================================
     */

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {

        return build(
                HttpStatus.FORBIDDEN,
                "You do not have permission to access this resource",
                request
        );
    }

    /*
     * =========================================================
     * 404 NOT FOUND
     * =========================================================
     */

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {

        return build(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {

        return build(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                request
        );
    }

    /*
     * =========================================================
     * 500 INTERNAL SERVER ERROR
     * =========================================================
     */

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(
            OptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {

        log.warn(
                "Concurrent modification on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.CONFLICT,
                "The resource was modified concurrently, please retry",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {

        /*
         * Full detail server-side only. The client gets a generic message so
         * stack traces, SQL and internals never reach the API surface.
         */
        log.error(
                "Unexpected error on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {

        ErrorResponse response = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                Instant.now()
        );

        return ResponseEntity
                .status(status)
                .body(response);
    }
}
