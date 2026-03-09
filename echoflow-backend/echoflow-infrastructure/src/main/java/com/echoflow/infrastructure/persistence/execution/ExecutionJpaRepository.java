package com.echoflow.infrastructure.persistence.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExecutionJpaRepository extends JpaRepository<ExecutionEntity, UUID> {

    Optional<ExecutionEntity> findByTaskId(UUID taskId);
}
