package com.safevision.back.service;

import com.safevision.back.dto.WorkerRequest;
import com.safevision.back.dto.WorkerResponse;
import com.safevision.back.model.Worker;
import com.safevision.back.repository.WorkerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class WorkerService {

    private final WorkerRepository workerRepository;

    public WorkerService(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    public Flux<WorkerResponse> findAll() {
        return workerRepository.findByActiveTrue().map(WorkerResponse::from);
    }

    public Mono<WorkerResponse> findById(Long id) {
        return workerRepository.findById(id)
                .filter(Worker::active)
                .map(WorkerResponse::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found")));
    }

    public Mono<WorkerResponse> create(WorkerRequest request, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        Worker worker = new Worker(null, request.siteId(), request.code(),
                request.firstName(), request.lastName(), request.role(),
                true, now, createdBy, now, createdBy);
        return workerRepository.save(worker).map(WorkerResponse::from);
    }

    public Mono<WorkerResponse> update(Long id, WorkerRequest request, String updatedBy) {
        return workerRepository.findById(id)
                .filter(Worker::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found")))
                .flatMap(existing -> {
                    Worker updated = new Worker(
                            existing.id(), request.siteId(), request.code(),
                            request.firstName(), request.lastName(), request.role(),
                            true, existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return workerRepository.save(updated);
                })
                .map(WorkerResponse::from);
    }

    public Mono<Void> delete(Long id, String updatedBy) {
        return workerRepository.findById(id)
                .filter(Worker::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found")))
                .flatMap(existing -> {
                    Worker deactivated = new Worker(
                            existing.id(), existing.siteId(), existing.code(),
                            existing.firstName(), existing.lastName(), existing.role(),
                            false, existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return workerRepository.save(deactivated);
                })
                .then();
    }
}
