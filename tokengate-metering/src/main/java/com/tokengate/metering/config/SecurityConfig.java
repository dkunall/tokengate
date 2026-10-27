package com.tokengate.metering.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Same JWT contract as the gateway's /admin: HS256 or an issuer URI, roles in a "roles" claim. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final Set<String> ROLES = Set.of("ADMIN", "BILLING", "VIEWER");

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtDecoder decoder) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/prometheus",
                                "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/admin/prices").hasAnyRole("ADMIN", "BILLING", "VIEWER")
                        .requestMatchers(HttpMethod.POST, "/admin/prices").hasRole("ADMIN")
                        .requestMatchers("/admin/usage/**", "/admin/invoices/**", "/admin/invoices")
                            .hasAnyRole("ADMIN", "BILLING")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)
                        .jwtAuthenticationConverter(SecurityConfig::withRoles)))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(MeteringProperties props) {
        MeteringProperties.Jwt jwt = props.jwt();
        if (jwt.issuerUri() != null && !jwt.issuerUri().isBlank()) {
            return JwtDecoders.fromIssuerLocation(jwt.issuerUri());
        }
        String secret = jwt.hmacSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("tokengate.metering.jwt.hmac-secret must be at least 32 characters");
        }
        if (secret.startsWith("dev-only")) {
            log.warn("tokengate.metering.jwt.hmac-secret is using the built-in development value; "
                    + "set it from the environment before real use");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwt.issuer()));
        return decoder;
    }

    private static AbstractAuthenticationToken withRoles(Jwt jwt) {
        List<String> claimed = jwt.getClaimAsStringList("roles");
        Collection<GrantedAuthority> roles = claimed == null ? List.of() : claimed.stream()
                .map(r -> r.toUpperCase(Locale.ROOT))
                .filter(ROLES::contains)
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new JwtAuthenticationToken(jwt, roles, jwt.getSubject());
    }
}
