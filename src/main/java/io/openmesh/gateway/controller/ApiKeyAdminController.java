package io.openmesh.gateway.controller;

import io.openmesh.gateway.model.ApiKeyCreateRequest;
import io.openmesh.gateway.model.ApiKeyRecord;
import io.openmesh.gateway.service.ApiKeyService;
import io.openmesh.gateway.service.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;

/** API key lifecycle. The plaintext key is returned only once, in the create response. */
@RestController
@RequestMapping("/admin/v1/api-keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;
    private final AuditService audit;

    @GetMapping
    public Flux<ApiKeyRecord> list() {
        return apiKeyService.list();
    }

    @PostMapping
    public Mono<ResponseEntity<ApiKeyService.CreatedKey>> create(@Valid @RequestBody ApiKeyCreateRequest request,
                                                                 Principal principal) {
        return apiKeyService.create(request, principal.getName())
                .flatMap(created -> audit.record(principal.getName(), "API_KEY_CREATED", created.record().getId(),
                                "tenant=" + created.record().getTenantId() + " tier=" + created.record().getTier())
                        .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(created)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> revoke(@PathVariable String id, Principal principal) {
        return apiKeyService.revoke(id)
                .flatMap(record -> audit.record(principal.getName(), "API_KEY_REVOKED", id, "tenant=" + record.getTenantId()))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
