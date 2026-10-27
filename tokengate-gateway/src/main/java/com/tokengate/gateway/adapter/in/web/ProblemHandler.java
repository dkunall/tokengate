package com.tokengate.gateway.adapter.in.web;

import com.tokengate.gateway.application.chat.Attempt;
import com.tokengate.gateway.application.error.BudgetExceededException;
import com.tokengate.gateway.application.error.ConflictException;
import com.tokengate.gateway.application.error.NoProviderAvailableException;
import com.tokengate.gateway.application.error.NotFoundException;
import com.tokengate.gateway.application.error.RateLimitedException;
import com.tokengate.gateway.application.error.UnknownModelException;
import com.tokengate.gateway.application.error.UpstreamFailedException;
import com.tokengate.gateway.domain.budget.BudgetDecision;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/** Every error leaves as application/problem+json (RFC 7807) with a stable type and the request id. */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        GatewayHeaders.rateLimit(h, e.decision());
        h.set(HttpHeaders.RETRY_AFTER, Long.toString(e.decision().retryAfterSeconds()));
        return problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Rate limit exceeded",
                "Request rate for this tenant is over its limit. Retry after " + e.decision().retryAfterSeconds()
                        + "s.", req, h);
    }

    @ExceptionHandler(BudgetExceededException.class)
    ResponseEntity<ProblemDetail> budget(BudgetExceededException e, HttpServletRequest req) {
        BudgetDecision d = e.decision();
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, d.retryAfter().toSeconds())));
        String detail = d.reason() == BudgetDecision.Reason.DAILY_TOKENS
                ? "Daily token budget used: " + d.tokensUsed() + " of " + d.tokensLimit() + " tokens."
                : "Monthly cost cap reached: " + d.costUsedMicros() + " of " + d.costCapMicros() + " micro-USD.";
        ResponseEntity<ProblemDetail> r = problem(HttpStatus.TOO_MANY_REQUESTS, "budget-exceeded",
                "Budget exhausted", detail, req, h);
        r.getBody().setProperty("reason", d.reason().name());
        return r;
    }

    @ExceptionHandler(UnknownModelException.class)
    ResponseEntity<ProblemDetail> unknownModel(UnknownModelException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "unknown-model", "Unknown model", e.getMessage(), req, null);
    }

    @ExceptionHandler(NoProviderAvailableException.class)
    ResponseEntity<ProblemDetail> noProvider(NoProviderAvailableException e, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "5");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "no-provider", "No provider available", e.getMessage(),
                req, h);
    }

    @ExceptionHandler(UpstreamFailedException.class)
    ResponseEntity<ProblemDetail> upstream(UpstreamFailedException e, HttpServletRequest req) {
        boolean timeout = e.last() != null && e.last().kind() == FailureKind.TIMEOUT;
        ResponseEntity<ProblemDetail> r = problem(timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                "upstream-failed", "All providers failed", e.getMessage(), req, null);
        r.getBody().setProperty("attempts", e.attempts().stream().map(Attempt::describe).toList());
        return r;
    }

    @ExceptionHandler(ProviderFailure.class)
    ResponseEntity<ProblemDetail> providerRejected(ProviderFailure e, HttpServletRequest req) {
        if (e.kind() == FailureKind.BAD_REQUEST) {
            return problem(HttpStatus.BAD_REQUEST, "upstream-rejected", "Provider rejected the request",
                    e.getMessage(), req, null);
        }
        return problem(HttpStatus.BAD_GATEWAY, "upstream-failed", "Provider failed", e.getMessage(), req, null);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage(), req, null);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", e.getMessage(), req, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage(), req, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal error",
                "Something went wrong on our side.", req, null);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail pd && request instanceof ServletWebRequest swr) {
            pd.setProperty("request_id", RequestIdFilter.of(swr.getRequest()));
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    static ProblemDetail detail(HttpStatus status, String type, String title, String detail, String requestId) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:tokengate:problem:" + type));
        pd.setTitle(title);
        pd.setProperty("request_id", requestId);
        return pd;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String title, String detail,
                                                         HttpServletRequest req, HttpHeaders headers) {
        ProblemDetail pd = detail(status, type, title, detail, RequestIdFilter.of(req));
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(status).headers(headers).body(pd);
    }
}
