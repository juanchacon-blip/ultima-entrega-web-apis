package co.edu.unibague.tasks;

import co.edu.unibague.tasks.dto.TaskRequest;
import co.edu.unibague.tasks.dto.TaskResponse;
import co.edu.unibague.tasks.model.Task;
import co.edu.unibague.tasks.repository.TaskRepository;
import co.edu.unibague.tasks.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    TaskRepository repository;

    TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(repository);
    }

    private static void assertNotFound(Throwable t) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void create_assignsOwnerAndDefaultsCompletedToFalse() {
        when(repository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskResponse created = service.create(new TaskRequest("Estudiar", "Spring Boot", null), "maria");

        assertThat(created.owner()).isEqualTo("maria");
        assertThat(created.title()).isEqualTo("Estudiar");
        assertThat(created.completed()).isFalse();
    }

    @Test
    void list_asUser_onlyReturnsOwnTasks() {
        when(repository.findByOwnerOrderByIdAsc("maria"))
                .thenReturn(List.of(new Task("mia", null, false, "maria")));

        List<TaskResponse> result = service.list("maria", false);

        assertThat(result).hasSize(1);
        verify(repository, never()).findAll();
    }

    @Test
    void list_asAdmin_returnsEveryTask() {
        when(repository.findAll()).thenReturn(List.of(
                new Task("a", null, false, "maria"),
                new Task("b", null, false, "pedro")));

        assertThat(service.list("admin", true)).hasSize(2);
    }

    @Test
    void get_taskOfAnotherUser_returns404() {
        when(repository.findById(1L)).thenReturn(Optional.of(new Task("ajena", null, false, "pedro")));

        assertThatThrownBy(() -> service.get(1L, "maria", false)).satisfies(TaskServiceTest::assertNotFound);
    }

    @Test
    void get_taskOfAnotherUser_asAdmin_isAllowed() {
        when(repository.findById(1L)).thenReturn(Optional.of(new Task("ajena", null, false, "pedro")));

        assertThat(service.get(1L, "admin", true).owner()).isEqualTo("pedro");
    }

    @Test
    void update_asOwner_changesFields() {
        Task existing = new Task("viejo", "desc", false, "maria");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskResponse updated = service.update(1L, new TaskRequest("nuevo", "otra", true), "maria", false);

        assertThat(updated.title()).isEqualTo("nuevo");
        assertThat(updated.description()).isEqualTo("otra");
        assertThat(updated.completed()).isTrue();
    }

    @Test
    void update_missingTask_returns404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new TaskRequest("x", null, false), "maria", false))
                .satisfies(TaskServiceTest::assertNotFound);
    }

    @Test
    void delete_asOwner_deletesTask() {
        Task existing = new Task("borrar", null, false, "maria");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        service.delete(1L, "maria", false);

        verify(repository).delete(existing);
    }

    @Test
    void delete_taskOfAnotherUser_returns404AndDoesNotDelete() {
        when(repository.findById(1L)).thenReturn(Optional.of(new Task("ajena", null, false, "pedro")));

        assertThatThrownBy(() -> service.delete(1L, "maria", false)).satisfies(TaskServiceTest::assertNotFound);
        verify(repository, never()).delete(any(Task.class));
    }
}
