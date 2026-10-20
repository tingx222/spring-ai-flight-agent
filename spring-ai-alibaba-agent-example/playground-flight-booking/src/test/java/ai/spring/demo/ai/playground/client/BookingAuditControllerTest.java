package ai.spring.demo.ai.playground.client;

import ai.spring.demo.ai.playground.services.FlightBookingService;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingAuditControllerTest {
    @TempDir Path output;
    private static final String TYPE = "ai.spring.demo.ai.playground.client.BookingAuditController";
    private Path sourceFile() {
        String relative = "src/main/java/ai/spring/demo/ai/playground/client/BookingAuditController.java";
        Path current = Path.of(System.getProperty("user.dir"));
        Path direct = current.resolve(relative);
        if (Files.exists(direct)) return direct;
        Path fromRoot = current.resolve("spring-ai-alibaba-agent-example/playground-flight-booking").resolve(relative);
        assertTrue(Files.exists(fromRoot), "Run from the module or project root");
        return fromRoot;
    }
    private void verifyBinding(String uri, String expectedNumber) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require JDK17, not a standalone JRE");
        // 故意不传-parameters；编译的是当前真实Controller源码，不是测试替身。
        int code = compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-g:none",
            "-classpath", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            "-d", output.toString(), sourceFile().toString());
        assertEquals(0, code);
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE)) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) result = findClass(name);
                if (resolve) resolveClass(result);
                return result;
            }
        }) {
            Class<?> type = loader.loadClass(TYPE);
            assertFalse(type.getDeclaredMethod("list", String.class).getParameters()[0].isNamePresent());
            var service = mock(FlightBookingService.class);
            when(service.getAuditRecords(expectedNumber)).thenReturn(List.of());
            Object controller = type.getConstructor(FlightBookingService.class).newInstance(service);
            WebTestClient.bindToController(controller).build().get().uri(uri).exchange()
                .expectStatus().isOk().expectBody().json("[]");
            verify(service).getAuditRecords(expectedNumber);
        }
    }
    @Test void filteredRequestWorksWithoutParameterMetadata() throws Exception {
        verifyBinding("/api/booking-audits?bookingNumber=104", "104");
    }
    @Test void unfilteredRequestWorksWithoutParameterMetadata() throws Exception {
        verifyBinding("/api/booking-audits", null);
    }
}
