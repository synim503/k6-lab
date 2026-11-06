package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebSecurityIT extends AbstractIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayMigratedAndAdminBootstrapped() {
        Integer tables = jdbc.queryForObject("select count(*) from information_schema.tables where table_name in "
                + "('app_user','generator','ssh_credential','known_host','distribution_artifact','deployment','generator_metrics')",
                Integer.class);
        assertThat(tables).isEqualTo(7);
        String hash = jdbc.queryForObject("select password_hash from app_user where username = 'admin'", String.class);
        assertThat(hash).startsWith("$2").isNotEqualTo(ADMIN_PASSWORD); // BCrypt, а не открытый пароль
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/generators")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Войти")));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void formLoginWorksAndWrongPasswordFails() throws Exception {
        mvc.perform(post("/login").param("username", "admin").param("password", ADMIN_PASSWORD).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").param("username", "admin").param("password", "wrong").with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void csrfIsEnforced() throws Exception {
        mvc.perform(post("/login").param("username", "admin").param("password", ADMIN_PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotReachAdminSections() throws Exception {
        for (String path : new String[]{"/distributions", "/ssh-credentials", "/users", "/generators/new"}) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/generators/update-all").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/generators/1/restart").with(csrf())).andExpect(status().isForbidden());
        // но просматривать генераторы и операции можно
        mvc.perform(get("/generators")).andExpect(status().isOk());
        mvc.perform(get("/deployments")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPagesRender() throws Exception {
        for (String path : new String[]{"/", "/generators", "/generators/table", "/generators/new", "/generators/bulk",
                "/deployments", "/distributions", "/ssh-credentials", "/ssh-credentials/new", "/users", "/tuning", "/tuning/table"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void tuningIsForbiddenForNonAdmins() throws Exception {
        mvc.perform(get("/tuning")).andExpect(status().isForbidden());
        mvc.perform(post("/tuning/apply").with(csrf()).param("generatorIds", "1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void sshSecretsAreStoredEncryptedAndNeverRendered() throws Exception {
        mvc.perform(post("/ssh-credentials").with(csrf())
                        .param("name", "it-cred").param("username", "deploy").param("authType", "PASSWORD")
                        .param("secret", "TopSecretPassw0rd").param("sudoPassword", "SudoSecret123"))
                .andExpect(status().is3xxRedirection());
        byte[] enc = jdbc.queryForObject("select secret_enc from ssh_credential where name = 'it-cred'", byte[].class);
        assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("TopSecret");
        Long id = jdbc.queryForObject("select id from ssh_credential where name = 'it-cred'", Long.class);

        String list = mvc.perform(get("/ssh-credentials")).andReturn().getResponse().getContentAsString();
        String edit = mvc.perform(get("/ssh-credentials/" + id + "/edit")).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("TopSecretPassw0rd").doesNotContain("SudoSecret123");
        assertThat(edit).doesNotContain("TopSecretPassw0rd").doesNotContain("SudoSecret123").contains("не показывается");

        // пустой секрет при редактировании не затирает сохранённый
        mvc.perform(post("/ssh-credentials").with(csrf()).param("id", String.valueOf(id))
                        .param("name", "it-cred").param("username", "deploy2").param("authType", "PASSWORD").param("secret", ""))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select secret_enc from ssh_credential where id = ?", byte[].class, id)).isEqualTo(enc);
        assertThat(jdbc.queryForObject("select username from ssh_credential where id = ?", String.class, id)).isEqualTo("deploy2");
    }
}
