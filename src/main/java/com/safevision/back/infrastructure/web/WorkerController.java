package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.WorkerService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.WorkerRequest;
import com.safevision.back.infrastructure.web.dto.WorkerResponse;
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
@RequestMapping("/workers")
@Tag(name = "Workers", description = "Gestión de trabajadores en obra")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping
    @Operation(summary = "Listar trabajadores activos")
    @ApiResponse(responseCode = "200", description = "Lista de trabajadores")
    public Mono<ApiEnvelope<List<WorkerResponse>>> findAll() {
        return ApiEnvelope.wrapList(workerService.findAll(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de trabajador")
    @ApiResponse(responseCode = "200", description = "Trabajador encontrado")
    public Mono<ApiEnvelope<WorkerResponse>> findById(@PathVariable Long id) {
        return ApiEnvelope.wrap(workerService.findById(id), HttpStatus.OK);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear trabajador", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Trabajador creado")
    public Mono<ApiEnvelope<WorkerResponse>> create(@Valid @RequestBody WorkerRequest request,
                                       @RequestAttribute("username") String username) {
        return ApiEnvelope.wrap(workerService.create(request, username), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar trabajador", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Trabajador actualizado")
    public Mono<ApiEnvelope<WorkerResponse>> update(@PathVariable Long id,
                                       @Valid @RequestBody WorkerRequest request,
                                       @RequestAttribute("username") String username) {
        return ApiEnvelope.wrap(workerService.update(id, request, username), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar trabajador (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Trabajador desactivado")
    public Mono<ApiEnvelope<Void>> delete(@PathVariable Long id,
                             @RequestAttribute("username") String username) {
        return ApiEnvelope.wrapVoid(workerService.delete(id, username), HttpStatus.OK);
    }
}
