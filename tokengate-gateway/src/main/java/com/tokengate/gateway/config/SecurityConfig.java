package com.tokengate.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.adapter.in.web.RequestIdFilter;
import com.tokengate.gateway.adapter.in.web.security.ApiKeyAuthenticationFilter;
import com.tokengate.gateway.adapter.in.web.security.ProblemAuthenticationEntryPoint;
import com.tokengate.gateway.application.auth.ApiKeyAuthenticator;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;

import javax.crypto.spec.SecretKeySpec;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Two chains: /v1 takes tenant API keys, /admin takes JWTs with a roles claim. Everything else is health,
 * metrics and API docs, which are open on the assumption that the port is not public (see the README).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return reg;
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http, ApiKeyAuthenticator authenticator, ObjectMapper json)
            throws Exception {
        ApiKeyAuthenticationFilter apiKeys = new ApiKeyAuthenticationFilter(authenticator, json);
        return stateless(http.securityMatcher("/v1/**"))
                .addFilterBefore(apiKeys, AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().hasRole("TENANT"))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain adminChain(HttpSecurity http, JwtDecoder decoder, ObjectMapper json) throws Exception {
        ProblemAuthenticationEntryPoint problems = new ProblemAuthenticationEntryPoint(json);
        return stateless(http.securityMatcher("/admin/**"))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/admin/tenants/*/budget").hasAnyRole("ADMIN", "BILLING")
                        .requestMatchers(HttpMethod.GET, "/admin/**").hasAnyRole("ADMIN", "VIEWER")
                        .anyRequest().hasRole("ADMIN"))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(SecurityConfig::withRoles)))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        return stateless(http)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/prometheus",
                                "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/error").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    JwtDecoder adminJwtDecoder(GatewayProperties props) {
        GatewayProperties.Jwt jwt = props.security().jwt();
        if (jwt.issuerUri() != null && !jwt.issuerUri().isBlank()) {
            return JwtDecoders.fromIssuerLocation(jwt.issuerUri());
        }
        byte[] key = GatewayConfig.secret("tokengate.security.jwt.hmac-secret", jwt.hmacSecret());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwt.issuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    private static AbstractAuthenticationToken withRoles(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, roles(jwt), jwt.getSubject());
    }

    static Collection<GrantedAuthority> roles(Jwt jwt) {
        List<String> claimed = jwt.getClaimAsStringList("roles");
        if (claimed == null) {
            return List.of();
        }
        return claimed.stream()
                .map(r -> r.toUpperCase(Locale.ROOT))
                .filter(r -> r.equals("ADMIN") || r.equals("BILLING") || r.equals("VIEWER"))
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}
