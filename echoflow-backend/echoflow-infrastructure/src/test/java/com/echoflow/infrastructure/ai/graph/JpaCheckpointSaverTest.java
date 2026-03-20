package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.echoflow.infrastructure.persistence.checkpoint.CheckpointEntity;
import com.echoflow.infrastructure.persistence.checkpoint.CheckpointJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaCheckpointSaverTest {

    @Mock
    private CheckpointJpaRepository checkpointRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JpaCheckpointSaver saver;

    @BeforeEach
    void setUp() {
        saver = new JpaCheckpointSaver(checkpointRepo, objectMapper);
    }

    @Test
    void insertedCheckpoint_saves_entity_to_database() throws Exception {
        var config = RunnableConfig.builder().threadId("exec-123").build();
        var checkpoint = Checkpoint.builder()
                .nodeId("step_1")
                .nextNodeId("step_2")
                .state(Map.of("taskDescription", "test task", "outputs", List.of("output1")))
                .build();

        when(checkpointRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        saver.insertedCheckpoint(config, new LinkedList<>(), checkpoint);

        var captor = ArgumentCaptor.forClass(CheckpointEntity.class);
        verify(checkpointRepo).save(captor.capture());
        var entity = captor.getValue();

        assertThat(entity.getThreadId()).isEqualTo("exec-123");
        assertThat(entity.getNodeId()).isEqualTo("step_1");
        assertThat(entity.getNextNodeId()).isEqualTo("step_2");
        assertThat(entity.getState()).contains("test task");
        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    void loadedCheckpoints_loads_from_database() throws Exception {
        var config = RunnableConfig.builder().threadId("exec-456").build();
        var cpId = UUID.randomUUID();
        var stateJson = objectMapper.writeValueAsString(
                Map.of("taskDescription", "loaded task"));
        var entity = new CheckpointEntity(cpId, "exec-456", "step_2", "step_3",
                stateJson, Instant.now());

        when(checkpointRepo.findByThreadIdOrderByCreatedAtAsc("exec-456"))
                .thenReturn(List.of(entity));

        var checkpoints = new LinkedList<Checkpoint>();
        var result = saver.loadedCheckpoints(config, checkpoints);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getId()).isEqualTo(cpId.toString());
        assertThat(result.getFirst().getNodeId()).isEqualTo("step_2");
        assertThat(result.getFirst().getNextNodeId()).isEqualTo("step_3");
        assertThat(result.getFirst().getState()).containsEntry("taskDescription", "loaded task");
    }

    @Test
    void updatedCheckpoint_saves_new_entity() throws Exception {
        var config = RunnableConfig.builder().threadId("exec-789").build();
        var checkpoint = Checkpoint.builder()
                .nodeId("step_3")
                .nextNodeId("__END__")
                .state(Map.of("taskDescription", "updated"))
                .build();

        when(checkpointRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        saver.updatedCheckpoint(config, new LinkedList<>(), checkpoint);

        verify(checkpointRepo).save(any(CheckpointEntity.class));
    }

    @Test
    void releasedCheckpoints_deletes_by_threadId() throws Exception {
        var config = RunnableConfig.builder().threadId("exec-cleanup").build();
        var tag = new BaseCheckpointSaver.Tag("exec-cleanup", List.of());

        saver.releasedCheckpoints(config, new LinkedList<>(), tag);

        verify(checkpointRepo).deleteByThreadId("exec-cleanup");
    }

    @Test
    void insertedCheckpoint_uses_default_threadId_when_absent() throws Exception {
        var config = RunnableConfig.builder().build();
        var checkpoint = Checkpoint.builder()
                .nodeId("step_1")
                .nextNodeId("step_2")
                .state(Map.of("key", "value"))
                .build();

        when(checkpointRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        saver.insertedCheckpoint(config, new LinkedList<>(), checkpoint);

        var captor = ArgumentCaptor.forClass(CheckpointEntity.class);
        verify(checkpointRepo).save(captor.capture());
        assertThat(captor.getValue().getThreadId()).isEqualTo("$default");
    }

    @Test
    void insertedCheckpoint_swallows_persistence_failure() throws Exception {
        var config = RunnableConfig.builder().threadId("failing").build();
        var checkpoint = Checkpoint.builder()
                .nodeId("step_1")
                .nextNodeId("step_2")
                .state(Map.of("key", "value"))
                .build();

        when(checkpointRepo.save(any())).thenThrow(new RuntimeException("DB down"));

        // Should not throw — checkpoint persistence is non-critical
        saver.insertedCheckpoint(config, new LinkedList<>(), checkpoint);

        verify(checkpointRepo).save(any());
    }
}
