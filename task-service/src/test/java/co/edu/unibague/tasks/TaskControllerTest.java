package co.edu.unibague.tasks;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba la API REST (POST, GET, PUT, DELETE) con seguridad real. El JWT se simula con
 * spring-security-test; la validación de firma RS256 se prueba en auth-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskControllerTest {

    private static final String BODY = "{\"title\":\"Entregar proyecto\",\"description\":\"Subir enlace\",\"completed\":false}";

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor asUser(String username) {
        return jwt().jwt(j -> j.subject(username)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor asAdmin(String username) {
        return jwt().jwt(j -> j.subject(username)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long createTask(RequestPostProcessor auth) throws Exception {
        String response = mvc.perform(post("/api/tasks").with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    @Test
    void withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
    }

    @Test
    void fullCrudFlow_asOwner() throws Exception {
        RequestPostProcessor maria = asUser("maria");
        long id = createTask(maria);

        mvc.perform(get("/api/tasks/" + id).with(maria))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("maria"))
                .andExpect(jsonPath("$.completed").value(false));

        mvc.perform(put("/api/tasks/" + id).with(maria).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Entregado\",\"description\":\"Listo\",\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Entregado"))
                .andExpect(jsonPath("$.completed").value(true));

        mvc.perform(get("/api/tasks").with(maria))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + id + ")]").isNotEmpty());

        mvc.perform(delete("/api/tasks/" + id).with(maria)).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/" + id).with(maria)).andExpect(status().isNotFound());
    }

    @Test
    void create_withBlankTitle_returns400() throws Exception {
        mvc.perform(post("/api/tasks").with(asUser("maria")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void user_cannotReadUpdateOrDeleteTasksOfAnotherUser() throws Exception {
        long id = createTask(asUser("maria"));
        RequestPostProcessor pedro = asUser("pedro");

        mvc.perform(get("/api/tasks/" + id).with(pedro)).andExpect(status().isNotFound());
        mvc.perform(put("/api/tasks/" + id).with(pedro).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/tasks/" + id).with(pedro)).andExpect(status().isNotFound());
    }

    @Test
    void list_asUser_doesNotIncludeTasksOfOthers() throws Exception {
        createTask(asUser("ana"));
        createTask(asUser("luis"));

        mvc.perform(get("/api/tasks").with(asUser("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.owner=='ana')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.owner=='luis')]").isEmpty());
    }

    @Test
    void list_asAdmin_includesTasksOfEveryone() throws Exception {
        createTask(asUser("ana"));
        createTask(asUser("luis"));

        mvc.perform(get("/api/tasks").with(asAdmin("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.owner=='ana')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.owner=='luis')]").isNotEmpty());
    }
}
