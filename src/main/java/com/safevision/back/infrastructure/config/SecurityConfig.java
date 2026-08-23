package com.safevision.back.infrastructure.config;

import com.safevision.back.infrastructure.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Prefijos de ruta que no requieren JWT de usuario. */
    private static final String[] PUBLIC_PATH_PREFIXES = {
            "/api/v1/auth/", "/swagger-ui", "/v3/api-docs", "/webjars/"
    };

    @Value("${app.security.alert-service-token}")
    private String alertServiceToken;

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

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
                                "/webjars/**",
                                "/api/v1/auth/**"
                        ).permitAll()
                        .anyExchange().permitAll()
                )
                .addFilterBefore(alertTokenFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterBefore(jwtAuthFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    /**
     * Valida el Bearer token estático para el endpoint de registro de incidentes.
     * Solo aplica a POST /api/v1/incidents (llamado exclusivamente por el módulo CV).
     */
    private WebFilter alertTokenFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            if (isIncidentIngestEndpoint(exchange)) {
                String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if (!("Bearer " + alertServiceToken).equals(auth)) {
                    log.warn("Incidente rechazado | Bearer token inválido o ausente | remote={}",
                            exchange.getRequest().getRemoteAddress());
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
            }
            return chain.filter(exchange);
        };
    }

    /**
     * Valida el JWT de usuario (emitido por POST /api/v1/auth/login) en el resto
     * de endpoints. Rutas públicas ({@link #PUBLIC_PATH_PREFIXES}) y el endpoint
     * de ingesta de incidentes (que usa su propio token estático, ver
     * {@link #alertTokenFilter()}) quedan afuera de esta validación.
     */
    private WebFilter jwtAuthFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            if (isPublicPath(exchange) || isIncidentIngestEndpoint(exchange)) {
                return chain.filter(exchange);
            }
            String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
            if (token == null || !jwtService.isValid(token)) {
                log.warn("Acceso rechazado | JWT inválido o ausente | path={} remote={}",
                        exchange.getRequest().getPath(), exchange.getRequest().getRemoteAddress());
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private boolean isIncidentIngestEndpoint(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == HttpMethod.POST
                && exchange.getRequest().getPath().value().equals("/api/v1/incidents");
    }

    private boolean isPublicPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
