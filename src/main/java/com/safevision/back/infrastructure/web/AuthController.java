package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.AuthService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.LoginRequest;
import com.safevision.back.infrastructure.web.dto.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Autenticación de usuarios del frontend")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Login")
    @ApiResponse(responseCode = "200", description = "Login correcto, retorna token")
    public Mono<ApiEnvelope<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ApiEnvelope.wrap(authService.login(request), HttpStatus.OK);
    }
}
