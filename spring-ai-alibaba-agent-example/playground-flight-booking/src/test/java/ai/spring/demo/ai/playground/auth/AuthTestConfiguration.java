package ai.spring.demo.ai.playground.auth;

import ai.spring.demo.ai.playground.services.*;
import ai.spring.demo.ai.playground.repositories.JdbcBookingRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.session.MapSession;
import org.springframework.session.ReactiveMapSessionRepository;
import org.springframework.session.config.annotation.web.server.EnableSpringWebSession;
import java.util.concurrent.ConcurrentHashMap;
import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods=false) @EnableAutoConfiguration
@Import({SecurityConfiguration.class,AccountService.class,AuthController.class,ApiErrors.class,AuditActor.class,
    BookingAccessService.class,SecureBookingController.class,JdbcBookingRepository.class,FlightBookingService.class,
    OperationConfirmationService.class,BookingTools.class,AuthTestConfiguration.MemorySessions.class})
public class AuthTestConfiguration {
    @Bean @Primary CustomerSupportAssistant assistant() { return mock(CustomerSupportAssistant.class); }
    @TestConfiguration(proxyBeanMethods=false) @EnableSpringWebSession
    @ConditionalOnProperty(name="app.auth.session-store",havingValue="memory")
    static class MemorySessions {
        private static final ConcurrentHashMap<String, org.springframework.session.Session> SHARED = new ConcurrentHashMap<>();
        @Bean ReactiveMapSessionRepository sessions() { return new ReactiveMapSessionRepository(SHARED); }
    }
}
