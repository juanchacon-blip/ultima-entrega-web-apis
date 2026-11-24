package co.edu.unibague.auth;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    private static final String PASSWORD = "Password123";

    @Autowired
    MockMvc mvc;

    @Autowired
    RSAKey rsaKey;

    private static String credentials(String username, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
    }

    private ResultActions register(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    private String accessToken(String username, String password) throws Exception {
        String body = login(username, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    @Test
    void register_createsUserWithRoleUserAndNeverReturnsPassword() throws Exception {
        register("registro_ok", PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("registro_ok"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void register_duplicatedUsername_returns409() throws Exception {
        register("duplicado", PASSWORD).andExpect(status().isCreated());
        register("duplicado", PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void register_shortPassword_returns400() throws Exception {
        register("clave_corta", "123").andExpect(status().isBadRequest());
    }

    @Test
    void login_returnsOAuth2TokenResponse() throws Exception {
        register("login_ok", PASSWORD).andExpect(status().isCreated());
        login("login_ok", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        register("login_mal", PASSWORD).andExpect(status().isCreated());
        login("login_mal", "OtraClave999").andExpect(status().isUnauthorized());
    }

    @Test
    void login_unknownUser_returns401() throws Exception {
        login("no_existe", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void token_isSignedWithRs256AndVerifiableWithPublicKey() throws Exception {
        register("token_rsa", PASSWORD).andExpect(status().isCreated());
        String token = accessToken("token_rsa", PASSWORD);

        Jwt jwt = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build().decode(token);

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getSubject()).isEqualTo("token_rsa");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        Object issuer = jwt.getClaim("iss");
        assertThat(issuer.toString()).isEqualTo("auth-service");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void token_isRejectedByAnotherRsaKey() throws Exception {
        register("token_otra_llave", PASSWORD).andExpect(status().isCreated());
        String token = accessToken("token_otra_llave", PASSWORD);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();
        NimbusJwtDecoder foreignDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) other.getPublic()).build();

        assertThatThrownBy(() -> foreignDecoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void seededAdmin_canLoginAndGetsAdminRole() throws Exception {
        String token = accessToken("admin", "Admin12345!");
        Jwt jwt = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build().decode(token);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void jwks_exposesOnlyThePublicKey() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }
}
