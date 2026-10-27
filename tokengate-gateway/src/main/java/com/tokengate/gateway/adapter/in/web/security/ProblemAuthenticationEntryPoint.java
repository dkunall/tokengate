package com.tokengate.gateway.adapter.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.adapter.in.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URI;

/** 401 and 403 on /admin in the same problem+json shape as everything else. */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public ProblemAuthenticationEntryPoint(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "A valid admin JWT is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Your role does not allow this operation.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String type,
                       String detail) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:tokengate:problem:" + type));
        pd.setTitle(status.getReasonPhrase());
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("request_id", RequestIdFilter.of(request));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), pd);
    }
}
