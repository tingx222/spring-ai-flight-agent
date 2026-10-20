package ai.spring.demo.ai.playground.auth;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the same cookie against two real HTTP servers; real Redis is explicitly opt-in with local credentials. */
class DistributedSessionTest {
    @Test void twoServersShareLoginAndLogoutThroughTestSessionRepository() { verifySharedSession(false); }
    @Test @EnabledIfEnvironmentVariable(named="RUN_REDIS_INTEGRATION",matches="true")
    void twoServersShareLoginAndLogoutThroughRealRedis() { verifySharedSession(true); }
    private void verifySharedSession(boolean redis) {
        String database="session_"+UUID.randomUUID().toString().replace("-","");
        String[] options={"--spring.profiles.active=mysql,secure","--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:"+database+";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver","--spring.datasource.username=sa","--spring.datasource.password=",
            "--spring.ai.dashscope.api-key=test-not-a-real-key","--app.rag.initialize=false",
            "--app.auth.bootstrap-admin-password=test-admin-password-123",
            "--app.auth.session-store="+(redis?"redis":"memory"),
            "--app.auth.redis-namespace=flight:test:session:"+database};
        try (var first=new SpringApplicationBuilder(AuthTestConfiguration.class).run(options);
             var second=new SpringApplicationBuilder(AuthTestConfiguration.class).run(options)) {
            if (redis) assertTrue(first.getBean(org.springframework.session.ReactiveSessionRepository.class).getClass().getSimpleName().contains("Redis"));
            var a=client(first); var b=client(second);
            var csrf=a.get().uri("/api/auth/csrf").exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
            String anonymous=csrf.getResponseCookies().getFirst("FLIGHT_SESSION").getValue();
            var login=a.post().uri("/api/auth/login").cookie("FLIGHT_SESSION",anonymous)
                .header(csrf.getResponseBody().get("headerName").toString(),csrf.getResponseBody().get("token").toString())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("username","admin","password","test-admin-password-123"))
                .exchange().expectStatus().isOk().expectBody().returnResult();
            String cookie=login.getResponseCookies().getFirst("FLIGHT_SESSION").getValue();
            var cookieValue=login.getResponseCookies().getFirst("FLIGHT_SESSION");
            assertTrue(cookieValue.isHttpOnly()); assertEquals("Lax",cookieValue.getSameSite());
            b.get().uri("/api/auth/me").cookie("FLIGHT_SESSION",cookie).exchange().expectStatus().isOk().expectBody().jsonPath("$.username").isEqualTo("admin");
            a.get().uri("/api/auth/me").cookie("FLIGHT_SESSION",anonymous).exchange().expectStatus().isUnauthorized();
            var token=b.get().uri("/api/auth/csrf").cookie("FLIGHT_SESSION",cookie).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            b.post().uri("/api/auth/logout").cookie("FLIGHT_SESSION",cookie)
                .header(token.get("headerName").toString(),token.get("token").toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of()).exchange().expectStatus().isNoContent();
            a.get().uri("/api/auth/me").cookie("FLIGHT_SESSION",cookie).exchange().expectStatus().isUnauthorized();
            b.get().uri("/api/auth/me").cookie("FLIGHT_SESSION",cookie).exchange().expectStatus().isUnauthorized();
        }
    }
    private WebTestClient client(ConfigurableApplicationContext context) {
        int port=((ReactiveWebServerApplicationContext)context).getWebServer().getPort();
        return WebTestClient.bindToServer().baseUrl("http://localhost:"+port).responseTimeout(java.time.Duration.ofSeconds(15)).build();
    }
}
