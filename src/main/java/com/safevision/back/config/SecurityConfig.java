package com.safevision.back.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${app.security.alert-service-token}")
    private String alertServiceToken;

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/webjars/**"
                        ).permitAll()
                        .anyExchange().permitAll()
                )
                .addFilterBefore(alertTokenFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    /**
     * Valida el Bearer token estático para el endpoint de registro de incidentes.
     * Solo aplica a POST /api/v1/incidents (llamado exclusivamente por el módulo CV).
     */
    private WebFilter alertTokenFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            if (isIncidentEndpoint(exchange)) {
                String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if (!("Bearer " + alertServiceToken).equals(auth)) {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
            }
            return chain.filter(exchange);
        };
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private boolean isIncidentEndpoint(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == HttpMethod.POST
                && exchange.getRequest().getPath().value().equals("/api/v1/incidents");
    }
}
