package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestApiIT extends AbstractIT {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousApiReturnsJson401AndAuthMeWorks() throws Exception {
        mvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));

        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    void jsonLoginWorksAndSpaRoutesForwardToIndex() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.isAdmin").value(true));

        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"bad-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));

        // Клиентские маршруты React SPA отдают index.html
        mvc.perform(get("/app"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/"));
        mvc.perform(get("/app/scripts/42/load"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void browserDocumentNavigationRedirectsRootToSpa() throws Exception {
        mvc.perform(get("/").header("Sec-Fetch-Dest", "document"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/"));

        // При ?classic=1 остаётся классический шаблон
        mvc.perform(get("/").param("classic", "1").header("Sec-Fetch-Dest", "document"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "user1", roles = "USER")
    void userRoleAccessAndJson403ForAdminApi() throws Exception {
        for (String okPath : new String[]{
                "/api/v1/dashboard",
                "/api/v1/scripts",
                "/api/v1/runs",
                "/api/v1/schedule",
                "/api/v1/generators",
                "/api/v1/deployments",
                "/api/v1/secrets"
        }) {
            mvc.perform(get(okPath)).andExpect(status().isOk());
        }

        for (String adminOnly : new String[]{
                "/api/v1/distributions",
                "/api/v1/ssh-credentials",
                "/api/v1/users",
                "/api/v1/settings",
                "/api/v1/audit",
                "/api/v1/tuning",
                "/api/v1/sandbox/init"
        }) {
            mvc.perform(get(adminOnly))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false));
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminEndpointsAndScriptRestLifecycleWorkWithoutLeakingSecrets() throws Exception {
        for (String path : new String[]{
                "/api/v1/dashboard",
                "/api/v1/settings",
                "/api/v1/users",
                "/api/v1/distributions",
                "/api/v1/tuning",
                "/api/v1/audit",
                "/api/v1/sandbox/init"
        }) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }

        // Создание SSH-учётки через REST и проверка отсутствия секрета в выдаче
        mvc.perform(post("/api/v1/ssh-credentials").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rest-ssh\",\"username\":\"root\",\"authType\":\"PASSWORD\",\"secret\":\"SuperSecret999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        String sshJson = mvc.perform(get("/api/v1/ssh-credentials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sshJson).contains("rest-ssh").doesNotContain("SuperSecret999");

        // Загрузка скрипта через REST и автосохранение в редакторе
        MockMultipartFile js = new MockMultipartFile(
                "file", "test.js", "text/javascript",
                "import http from 'k6/http';\nexport default function () { http.get('http://localhost'); }\n".getBytes(StandardCharsets.UTF_8));
        String createdJson = mvc.perform(multipart("/api/v1/scripts").file(js)
                        .param("name", "rest-api-script")
                        .param("entrypoint", "test.js")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(createdJson);
        long scriptId = node.path("scriptId").asLong();
        long versionId = node.path("versionId").asLong();

        mvc.perform(get("/api/v1/scripts/" + scriptId + "/load")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/scripts/" + scriptId + "/charts")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/scripts/" + scriptId + "/run-init")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/scripts/" + scriptId + "/versions/" + versionId + "/autosave").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"test.js\",\"content\":\"export default function () {}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.versionId").isNumber());
    }
}
