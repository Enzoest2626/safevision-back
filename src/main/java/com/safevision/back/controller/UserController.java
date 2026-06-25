package com.safevision.back.controller;

import com.safevision.back.dto.UserRequest;
import com.safevision.back.dto.UserResponse;
import com.safevision.back.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Gestión de usuarios del sistema")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Listar usuarios activos")
    @ApiResponse(responseCode = "200", description = "Lista de usuarios")
    public Flux<UserResponse> findAll() {
        return userService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de usuario")
    @ApiResponse(responseCode = "200", description = "Usuario encontrado")
    @ApiResponse(responseCode = "404", description = "Usuario no encontrado")
    public Mono<UserResponse> findById(@PathVariable Long id) {
        return userService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear usuario", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Usuario creado")
    public Mono<UserResponse> create(@Valid @RequestBody UserRequest request,
                                     @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return userService.create(request, username);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar usuario", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Usuario actualizado")
    @ApiResponse(responseCode = "404", description = "Usuario no encontrado")
    public Mono<UserResponse> update(@PathVariable Long id,
                                     @Valid @RequestBody UserRequest request,
                                     @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return userService.update(id, request, username);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar usuario (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Usuario desactivado")
    @ApiResponse(responseCode = "404", description = "Usuario no encontrado")
    public Mono<Void> delete(@PathVariable Long id,
                             @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return userService.delete(id, username);
    }
}
