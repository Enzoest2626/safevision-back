package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.UserService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.UserRequest;
import com.safevision.back.infrastructure.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/users")
@Tag(name = "Users", description = "Gestión de usuarios del sistema")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Listar usuarios activos")
    @ApiResponse(responseCode = "200", description = "Lista de usuarios")
    public Mono<ApiEnvelope<List<UserResponse>>> findAll() {
        return ApiEnvelope.wrapList(userService.findAll(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de usuario")
    @ApiResponse(responseCode = "200", description = "Usuario encontrado")
    public Mono<ApiEnvelope<UserResponse>> findById(@PathVariable Long id) {
        return ApiEnvelope.wrap(userService.findById(id), HttpStatus.OK);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear usuario", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Usuario creado")
    public Mono<ApiEnvelope<UserResponse>> create(@Valid @RequestBody UserRequest request,
                                     @RequestAttribute("username") String username) {
        return ApiEnvelope.wrap(userService.create(request, username), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar usuario", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Usuario actualizado")
    public Mono<ApiEnvelope<UserResponse>> update(@PathVariable Long id,
                                     @Valid @RequestBody UserRequest request,
                                     @RequestAttribute("username") String username) {
        return ApiEnvelope.wrap(userService.update(id, request, username), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar usuario (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Usuario desactivado")
    public Mono<ApiEnvelope<Void>> delete(@PathVariable Long id,
                             @RequestAttribute("username") String username) {
        return ApiEnvelope.wrapVoid(userService.delete(id, username), HttpStatus.OK);
    }
}
