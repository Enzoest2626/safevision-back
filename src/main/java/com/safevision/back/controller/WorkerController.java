package com.safevision.back.controller;

import com.safevision.back.dto.WorkerRequest;
import com.safevision.back.dto.WorkerResponse;
import com.safevision.back.service.WorkerService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/workers")
@Tag(name = "Workers", description = "Gestión de trabajadores en obra")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping
    @Operation(summary = "Listar trabajadores activos")
    @ApiResponse(responseCode = "200", description = "Lista de trabajadores")
    public Flux<WorkerResponse> findAll() {
        return workerService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de trabajador")
    @ApiResponse(responseCode = "200", description = "Trabajador encontrado")
    @ApiResponse(responseCode = "404", description = "Trabajador no encontrado")
    public Mono<WorkerResponse> findById(@PathVariable Long id) {
        return workerService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear trabajador", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Trabajador creado")
    public Mono<WorkerResponse> create(@Valid @RequestBody WorkerRequest request,
                                       @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return workerService.create(request, username);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar trabajador", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Trabajador actualizado")
    @ApiResponse(responseCode = "404", description = "Trabajador no encontrado")
    public Mono<WorkerResponse> update(@PathVariable Long id,
                                       @Valid @RequestBody WorkerRequest request,
                                       @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return workerService.update(id, request, username);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar trabajador (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Trabajador desactivado")
    @ApiResponse(responseCode = "404", description = "Trabajador no encontrado")
    public Mono<Void> delete(@PathVariable Long id,
                             @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return workerService.delete(id, username);
    }
}
