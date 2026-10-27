package com.tokengate.gateway.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Takes the caller's X-Request-Id if it looks sane, otherwise makes one. It goes into the MDC (so every JSON log
 * line carries it), the response header, and on to the provider.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "tokengate.requestId";
    private static final String MDC_KEY = "requestId";
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._:-]{8,64}");
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && ACCEPTABLE.matcher(incoming).matches() ? incoming : generate();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    public static String of(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id == null ? generate() : id.toString();
    }

    static String generate() {
        char[] out = new char[20];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return "req_" + new String(out);
    }
}
