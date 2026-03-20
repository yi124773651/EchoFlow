package com.echoflow.infrastructure.persistence.checkpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CheckpointJpaRepository extends JpaRepository<CheckpointEntity, UUID> {

    List<CheckpointEntity> findByThreadIdOrderByCreatedAtAsc(String threadId);

    @Modifying
    @Transactional
    void deleteByThreadId(String threadId);
}
