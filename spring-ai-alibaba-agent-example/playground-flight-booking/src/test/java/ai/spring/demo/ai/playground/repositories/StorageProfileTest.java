package ai.spring.demo.ai.playground.repositories;
import ai.spring.demo.ai.playground.AgentApplication;
import ai.spring.demo.ai.playground.services.FlightBookingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.*;

class StorageProfileTest {
    @Test
    void mysqlProfileLoadsJdbcStorage() {
        try (var context = new SpringApplicationBuilder(AgentApplication.class).run(
            "--spring.profiles.active=mysql", "--server.port=0",
            "--spring.ai.dashscope.api-key=test-only", "--app.rag.initialize=false",
            "--spring.datasource.url=jdbc:h2:mem:profile_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--spring.datasource.driver-class-name=org.h2.Driver")) {
            assertInstanceOf(JdbcBookingRepository.class, context.getBean(BookingRepository.class));
            assertEquals(5, context.getBean(FlightBookingService.class).getBookings().size());
        }
    }
    @Test
    void defaultProfileStillLoadsWithoutDatabase() {
        try (var context = new SpringApplicationBuilder(AgentApplication.class).run(
            "--spring.profiles.active=default", "--server.port=0",
            "--spring.ai.dashscope.api-key=test-only", "--app.rag.initialize=false")) {
            assertInstanceOf(MemoryBookingRepository.class, context.getBean(BookingRepository.class));
            var resolver = context.getBean(org.springframework.ai.tool.resolution.ToolCallbackResolver.class);
            var empty = new org.springframework.ai.chat.model.ToolContext(java.util.Map.of());
            assertTrue(resolver.resolve("cancelBooking").call("{\"bookingNumber\":\"102\",\"name\":\"李千问\"}", empty).contains("请提供"));
            var supplied = new org.springframework.ai.chat.model.ToolContext(java.util.Map.of(
                ai.spring.demo.ai.playground.services.BookingInputGuard.CONTEXT_KEY, java.util.Set.of("101")));
            assertTrue(resolver.resolve("getBookingDetails").call("{\"bookingNumber\":\"101\",\"name\":\"云小宝\"}", supplied).contains("查询成功"));
            var assistant = context.getBean(ai.spring.demo.ai.playground.services.CustomerSupportAssistant.class);
            assertTrue(assistant.chat("missing-id-test", "我是李千问，请取消10.21，成都到天津的票").blockFirst().contains("请提供"));
            var memory = context.getBean(org.springframework.ai.chat.memory.ChatMemory.class);
            assertEquals(2, memory.get("missing-id-test").size());
            assertTrue(memory.get("another-chat").isEmpty());
            assertEquals(5, context.getBean(FlightBookingService.class).getBookings().size());
        }
    }
}
