package ai.spring.demo.ai.playground.repositories;
import static org.junit.jupiter.api.Assertions.*;
import ai.spring.demo.ai.playground.data.BookingStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcBookingRepositoryTest {
    @Test
    void persistsChangesAcrossRepositoryRecreationAndDoesNotReseed() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var repository = new JdbcBookingRepository(jdbc, manager);
        repository.initialize();
        assertEquals(5, repository.findAll().size());
        var booking = repository.find("103", "张百炼").orElseThrow();
        LocalDate date = LocalDate.now().plusDays(30);
        repository.change("103", "张百炼", date, booking.getFrom(), booking.getTo());
        repository.cancel("102", "李千问");
        var restarted = new JdbcBookingRepository(new JdbcTemplate(source), manager);
        restarted.initialize();
        assertEquals(5, restarted.findAll().size());
        var changed = restarted.find("103", "张百炼").orElseThrow();
        assertEquals(date, changed.getDate());
        assertEquals(booking.getFrom(), changed.getFrom());
        assertEquals(booking.getTo(), changed.getTo());
        assertEquals(BookingStatus.CANCELLED, restarted.find("102", "李千问").orElseThrow().getBookingStatus());
        assertTrue(restarted.find("101", "不存在的用户").isEmpty());
        assertTrue(restarted.find("101' OR '1'='1", "云小宝").isEmpty());
    }
}
