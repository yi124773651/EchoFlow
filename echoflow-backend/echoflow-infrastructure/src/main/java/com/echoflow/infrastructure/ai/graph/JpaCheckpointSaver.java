package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.echoflow.infrastructure.persistence.checkpoint.CheckpointEntity;
import com.echoflow.infrastructure.persistence.checkpoint.CheckpointJpaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

/**
 * JPA-backed checkpoint saver that extends {@link MemorySaver} with database persistence.
 *
 * <p>MemorySaver maintains an in-memory {@code LinkedList<Checkpoint>} per thread.
 * This subclass hooks into the load/insert/update/release lifecycle to
 * sync with PostgreSQL via JPA. The in-memory cache is authoritative during
 * a single execution; the database provides durability across restarts.</p>
 *
 * <p>Checkpoint state ({@code Map<String, Object>}) is serialized to JSONB
 * using Jackson {@link ObjectMapper}.</p>
 *
 * <p>Package-private: only used by {@link GraphOrchestrator}.</p>
 */
class JpaCheckpointSaver extends MemorySaver {

    private static final Logger log = LoggerFactory.getLogger(JpaCheckpointSaver.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CheckpointJpaRepository checkpointRepo;
    private final ObjectMapper objectMapper;

    JpaCheckpointSaver(CheckpointJpaRepository checkpointRepo, ObjectMapper objectMapper) {
        super();
        this.checkpointRepo = checkpointRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    protected LinkedList<Checkpoint> loadedCheckpoints(RunnableConfig config,
                                                       LinkedList<Checkpoint> checkpoints) throws Exception {
        var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        var entities = checkpointRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        for (var entity : entities) {
            checkpoints.add(toCheckpoint(entity));
        }
        return checkpoints;
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config,
                                      LinkedList<Checkpoint> checkpoints,
                                      Checkpoint checkpoint) throws Exception {
        var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        try {
            checkpointRepo.save(toEntity(threadId, checkpoint));
        } catch (Exception e) {
            log.warn("Failed to persist checkpoint {} for thread {}: {}",
                    checkpoint.getId(), threadId, e.getMessage());
        }
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config,
                                     LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) throws Exception {
        var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        try {
            checkpointRepo.save(toEntity(threadId, checkpoint));
        } catch (Exception e) {
            log.warn("Failed to persist updated checkpoint {} for thread {}: {}",
                    checkpoint.getId(), threadId, e.getMessage());
        }
    }

    @Override
    protected void releasedCheckpoints(RunnableConfig config,
                                       LinkedList<Checkpoint> checkpoints,
                                       BaseCheckpointSaver.Tag releaseTag) throws Exception {
        try {
            checkpointRepo.deleteByThreadId(releaseTag.threadId());
        } catch (Exception e) {
            log.warn("Failed to release checkpoints for thread {}: {}",
                    releaseTag.threadId(), e.getMessage());
        }
    }

    private CheckpointEntity toEntity(String threadId, Checkpoint cp) throws JsonProcessingException {
        return new CheckpointEntity(
                UUID.fromString(cp.getId()),
                threadId,
                cp.getNodeId(),
                cp.getNextNodeId(),
                objectMapper.writeValueAsString(cp.getState()),
                Instant.now()
        );
    }

    private Checkpoint toCheckpoint(CheckpointEntity entity) throws JsonProcessingException {
        Map<String, Object> state = objectMapper.readValue(entity.getState(), MAP_TYPE);
        return Checkpoint.builder()
                .id(entity.getId().toString())
                .nodeId(entity.getNodeId())
                .nextNodeId(entity.getNextNodeId())
                .state(state)
                .build();
    }
}
