package ai.spring.demo.ai.playground.repositories;
import ai.spring.demo.ai.playground.data.*;
import ai.spring.demo.ai.playground.services.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutePreservationTest {
    private JdbcTemplate jdbc;
    private JdbcBookingRepository repository;
    private FlightBookingService service;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        repository = new JdbcBookingRepository(jdbc, new DataSourceTransactionManager(source));
        repository.initialize();
        jdbc.update("UPDATE flight_bookings SET departure='深圳', destination='上海' WHERE booking_number='101'");
        service = new FlightBookingService(repository);
    }
    @Test void nullAndBlankRouteArgumentsPreserveActualRoute() {
        for (String blank : new String[] {null, "", "  "}) {
            service.changeBooking("101", "云小宝", LocalDate.now().plusDays(20).toString(), blank, blank);
            var booking = service.getBookingDetails("101", "云小宝");
            assertEquals("深圳", booking.from()); assertEquals("上海", booking.to());
        }
        for (var audit : repository.findAudits("101")) {
            assertEquals("深圳", audit.after().departure()); assertEquals("上海", audit.after().destination());
        }
    }
    @Test void databaseWriteCannotClearRouteEvenWhenRepositoryCalledDirectly() {
        repository.change("101", "云小宝", LocalDate.now().plusDays(20), "", "");
        assertEquals("深圳", repository.find("101", "云小宝").orElseThrow().getFrom());
        assertEquals("上海", repository.find("101", "云小宝").orElseThrow().getTo());
    }
    @Test void routeChangeRejectedAndOriginalDataUnchanged() {
        var original = service.getBookingDetails("101", "云小宝");
        assertThrows(IllegalArgumentException.class, () -> service.changeBooking("101", "云小宝", LocalDate.now().plusDays(20).toString(), "北京", "天津"));
        assertEquals(original, service.getBookingDetails("101", "云小宝"));
        assertEquals("REJECTED", repository.findAudits("101").get(0).result());
    }
    @Test void startupRecoversMissingRouteFromAuditWithoutResettingDate() {
        var original = repository.find("101", "云小宝").orElseThrow();
        var before = BookingAuditRecord.snapshot(original);
        LocalDate changedDate = LocalDate.now().plusDays(20);
        jdbc.update("UPDATE flight_bookings SET flight_date=?, departure='', destination='' WHERE booking_number='101'", changedDate);
        var damaged = BookingAuditRecord.snapshot(repository.find("101", "云小宝").orElseThrow());
        repository.appendAudit(new BookingAuditRecord("101", "CHANGE", "SUCCESS", "legacy blank route", java.time.Instant.now(), before, damaged));
        repository.initialize();
        var repaired = repository.find("101", "云小宝").orElseThrow();
        assertEquals(changedDate, repaired.getDate());
        assertEquals("深圳", repaired.getFrom()); assertEquals("上海", repaired.getTo());
        assertEquals("REPAIR", repository.findAudits("101").get(0).action());
        assertEquals("", repository.findAudits("101").get(0).before().departure());
        repository.initialize();
        assertEquals(2, repository.findAudits("101").size());
    }
    @Test void missingRouteWithoutAuditIsNotInventedAndChangeIsRejected() {
        jdbc.update("UPDATE flight_bookings SET departure='', destination='' WHERE booking_number='101'");
        repository.initialize();
        assertEquals("", repository.find("101", "云小宝").orElseThrow().getFrom());
        assertThrows(IllegalArgumentException.class, () -> service.changeBooking("101", "云小宝", LocalDate.now().plusDays(20).toString(), "深圳", "上海"));
    }
    @Test void partialRouteIsNotCombinedWithMismatchedSnapshot() {
        var before = BookingAuditRecord.snapshot(repository.find("101", "云小宝").orElseThrow());
        repository.appendAudit(new BookingAuditRecord("101", "CHANGE", "SUCCESS", "old", java.time.Instant.now(), before, before));
        jdbc.update("UPDATE flight_bookings SET departure='北京', destination='' WHERE booking_number='101'");
        repository.initialize();
        assertEquals("", repository.find("101", "云小宝").orElseThrow().getTo());
    }
    @Test void memoryModeAlsoPreservesRoute() {
        var memory = new MemoryBookingRepository();
        var booking = memory.find("103", "张百炼").orElseThrow();
        String from = booking.getFrom(), to = booking.getTo();
        new FlightBookingService(memory).changeBooking("103", "张百炼", LocalDate.now().plusDays(20).toString(), null, "");
        assertEquals(from, booking.getFrom()); assertEquals(to, booking.getTo());
    }
    @Test void shorthandNameIsRecognizedOnlyFromUserMessages() {
        assertEquals("云小宝", BookingInputGuard.providedName(java.util.List.of(), "云小宝，101"));
        assertEquals("云小宝", BookingInputGuard.providedName(java.util.List.of(new org.springframework.ai.chat.messages.UserMessage("我叫云小宝，101")), "改签到10.22"));
        assertEquals("", BookingInputGuard.providedName(java.util.List.of(new org.springframework.ai.chat.messages.AssistantMessage("我是云小宝")), "101"));
    }
}
