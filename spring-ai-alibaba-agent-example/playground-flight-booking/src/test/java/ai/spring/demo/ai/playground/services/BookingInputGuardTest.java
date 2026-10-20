package ai.spring.demo.ai.playground.services;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ToolContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

class BookingInputGuardTest {
    @Test void onlyUserMessagesSupplyIdentifiers() {
        assertEquals(Set.of("102"), BookingInputGuard.collect(List.of(
            new AssistantMessage("预订号B123456"), new UserMessage("预订号102")), "确认取消"));
        assertTrue(BookingInputGuard.collect(List.of(), "我是李千问，请取消10.21，成都到天津的票").isEmpty());
        assertEquals(Set.of("103"), BookingInputGuard.collect(List.of(), "103"));
        assertEquals(Set.of("102"), BookingInputGuard.collect(List.of(), "订单号：102"));
        assertEquals(Set.of("102"), BookingInputGuard.collect(List.of(), "预订号\t102"));
    }
    @Test void absenceAndDifferentConversationDenyAccess() {
        assertFalse(BookingInputGuard.allows("102", null));
        assertFalse(BookingInputGuard.allows("102", new ToolContext(Map.of())));
        assertFalse(BookingInputGuard.allows("102", new ToolContext(Map.of(BookingInputGuard.CONTEXT_KEY, Set.of("103")))));
        assertTrue(BookingInputGuard.allows("102", new ToolContext(Map.of(BookingInputGuard.CONTEXT_KEY, Set.of("102")))));
    }
    @Test void missingNumberPreventsAllBusinessCalls() {
        var service = mock(FlightBookingService.class);
        var tools = new BookingTools();
        ReflectionTestUtils.setField(tools, "flightBookingService", service);
        var context = new ToolContext(Map.of(BookingInputGuard.CONTEXT_KEY, Set.of()));
        assertFalse(tools.getBookingDetails().apply(new BookingTools.BookingDetailsRequest("B123456", "李千问"), context).success());
        assertEquals(BookingInputGuard.MISSING, tools.cancelBooking().apply(new BookingTools.CancelBookingRequest("102", "李千问"), context));
        assertEquals(BookingInputGuard.MISSING, tools.changeBooking().apply(new BookingTools.ChangeBookingDatesRequest("102", "李千问", "2030-01-01", "成都", "天津"), context));
        verifyNoInteractions(service);
    }
    @Test void lookupFailureHasExplicitResultAndNoFabricatedDetails() {
        var service = mock(FlightBookingService.class);
        when(service.getBookingDetails("999", "李千问")).thenThrow(new IllegalArgumentException("Booking not found"));
        var tools = new BookingTools();
        ReflectionTestUtils.setField(tools, "flightBookingService", service);
        var result = tools.getBookingDetails().apply(new BookingTools.BookingDetailsRequest("999", "李千问"),
            new ToolContext(Map.of(BookingInputGuard.CONTEXT_KEY, Set.of("999"))));
        assertFalse(result.success());
        assertNull(result.booking());
        assertTrue(result.message().contains("未找到"));
    }
    @Test void termsQuestionsDoNotRequireAnOrder() {
        assertFalse(BookingInputGuard.needsOrder("取消有哪些规定？"));
        assertTrue(BookingInputGuard.needsOrder("我是李千问，请取消10.21，成都到天津的票"));
    }
}
