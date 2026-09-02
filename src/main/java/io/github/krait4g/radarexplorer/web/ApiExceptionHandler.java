package io.github.krait4g.radarexplorer.web;

import io.github.krait4g.radarexplorer.model.ApiModels.ErrorResponse;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.BadRequest;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.LimitExceeded;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.Unavailable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<ErrorResponse> badRequest(BadRequest exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(LimitExceeded.class)
    ResponseEntity<ErrorResponse> limitExceeded(LimitExceeded exception, HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "QUERY_ROW_LIMIT_EXCEEDED", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(Unavailable.class)
    ResponseEntity<ErrorResponse> unavailable(Unavailable exception, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Request parameters are invalid.",
                request,
                List.of(exception.getMessage())
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The requested resource was not found.", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected server error occurred.",
                request,
                List.of()
        );
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            List<String> details
    ) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), code, message,
                request.getRequestURI(), details
        );
        return ResponseEntity.status(status).body(body);
    }
}
