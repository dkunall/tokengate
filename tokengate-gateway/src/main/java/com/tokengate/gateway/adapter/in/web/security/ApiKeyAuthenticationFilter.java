package com.tokengate.gateway.adapter.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.adapter.in.web.RequestIdFilter;
import com.tokengate.gateway.application.auth.ApiKeyAuthenticator;
import com.tokengate.gateway.application.chat.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/** Bearer tg_... keys on /v1. A missing or bad key ends the request here with a 401 problem. */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyAuthenticator authenticator;
    private final ObjectMapper json;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, ObjectMapper json) {
        this.authenticator = authenticator;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Optional<Caller> caller = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authenticator.authenticate(header.substring(7).trim())
                : Optional.empty();
        if (caller.isEmpty()) {
            reject(request, response, header == null ? "Missing API key." : "Invalid or revoked API key.");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new CallerAuthentication(caller.get()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        pd.setType(URI.create("urn:tokengate:problem:unauthorized"));
        pd.setTitle("Unauthorized");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("request_id", RequestIdFilter.of(request));
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"tokengate\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), pd);
    }

    static final class CallerAuthentication extends AbstractAuthenticationToken {

        private final Caller caller;

        CallerAuthentication(Caller caller) {
            super(AuthorityUtils.createAuthorityList("ROLE_TENANT"));
            this.caller = caller;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return caller;
        }

        @Override
        public String getName() {
            return caller.tenant().id().value();
        }
    }
}
