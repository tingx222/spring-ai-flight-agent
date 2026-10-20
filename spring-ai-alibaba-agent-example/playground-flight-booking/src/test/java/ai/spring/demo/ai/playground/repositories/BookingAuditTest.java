package ai.spring.demo.ai.playground.repositories;
import ai.spring.demo.ai.playground.data.BookingStatus;
import ai.spring.demo.ai.playground.services.FlightBookingService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingAuditTest {
    private JdbcTemplate jdbc;
    private JdbcBookingRepository repository;
    private DriverManagerDataSource source;
    private FlightBookingService service;
    @BeforeEach void setUp() {
        source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        repository = new JdbcBookingRepository(jdbc, new DataSourceTransactionManager(source));
        repository.initialize();
        service = new FlightBookingService(repository);
    }
    @Test void changesAndAuditPersistTogetherAcrossRepositoryRecreation() {
        var original = repository.find("103", "张百炼").orElseThrow();
        LocalDate date = LocalDate.now().plusDays(30);
        service.changeBooking("103", "张百炼", date.toString(), original.getFrom(), original.getTo());
        var restarted = new JdbcBookingRepository(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        restarted.initialize();
        var audit = restarted.findAudits("103").get(0);
        assertEquals("CHANGE", audit.action());
        assertEquals("SUCCESS", audit.result());
        assertEquals(original.getDate().toString(), audit.before().date());
        assertEquals(date.toString(), audit.after().date());
        assertEquals(date, restarted.find("103", "张百炼").orElseThrow().getDate());
        assertNotNull(audit.occurredAt());
    }
    @Test void rejectedChangeHasAuditAndDoesNotModifyOrder() {
        var original = repository.find("103", "张百炼").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> service.changeBooking("103", "张百炼",
            LocalDate.now().minusDays(1).toString(), "北京", "上海"));
        var audit = repository.findAudits("103").get(0);
        assertEquals("REJECTED", audit.result());
        assertEquals(audit.before(), audit.after());
        assertEquals(original.getDate(), repository.find("103", "张百炼").orElseThrow().getDate());
    }
    @Test void repeatedCancellationIsAnAuditedNoop() {
        service.cancelBooking("102", "李千问");
        service.cancelBooking("102", "李千问");
        var audits = repository.findAudits("102");
        assertEquals(2, audits.size());
        assertEquals("NOOP", audits.get(0).result());
        assertEquals(audits.get(0).before(), audits.get(0).after());
        assertEquals("SUCCESS", audits.get(1).result());
        assertEquals("CONFIRMED", audits.get(1).before().status());
        assertEquals("CANCELLED", audits.get(1).after().status());
    }
    @Test
    void auditWriteFailureRollsBackOrderUpdate() {

        // 1. 【设下陷阱】：在数据库里手动修改表结构，给审计表加一个“强制约束”。
        // 意思是：以后任何人往 booking_audit 表插入数据，result 字段的值必须等于 'NEVER'。
        // 因为正常退票成功后日志写的都是 'SUCCESS'，所以只要执行正常退票，数据库一定会报错崩溃。
        jdbc.execute("ALTER TABLE booking_audit ADD CONSTRAINT reject_audit CHECK (result = 'NEVER')");

        // 2. 【触发爆炸】：调用业务代码，试图取消 102 号订单。
        // 我们使用 assertThrows 期望它抛出一个数据库异常（DataAccessException）。
        // 因为退票逻辑最后一步要写审计日志，一写就会撞上第 1 步设下的陷阱。
        // () -> service.cancelBooking(...) 就是把退票动作包在 Lambda 里交给 JUnit 去执行并捕获异常。
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.cancelBooking("102", "李千问"));

        // 3. 【验伤一：订单状态不能变】：去数据库里重新查询 102 号订单。
        // 虽然刚才业务代码确实执行了“改状态”的 SQL，但因为后续写日志报错，触发了 Spring 的事务回滚（Rollback）。
        // 所以，这里的断言要求：订单状态必须还是 CONFIRMED（已确认）。如果变成了 CANCELLED，说明事务失效了。
        assertEquals(BookingStatus.CONFIRMED, repository.find("102", "李千问").orElseThrow().getBookingStatus());

        // 4. 【验伤二：日志表必须为空】：去查一下审计日志表。
        // 刚才的写日志操作失败了，且整个事务回滚了，所以日志表里绝对不应该留下任何脏数据。
        assertTrue(repository.findAudits(null).isEmpty());
    }
    @Test void wrongNameDoesNotExposeActualOrderSnapshot() {
        assertThrows(IllegalArgumentException.class, () -> service.cancelBooking("102", "其他人"));
        var audit = repository.findAudits("102").get(0);
        assertEquals("REJECTED", audit.result());
        assertNull(audit.before());
        assertNull(audit.after());
    }
    @Test void memoryModeRecordsAuditAndResetsOnNewInstance() {
        var memory = new MemoryBookingRepository();
        new FlightBookingService(memory).cancelBooking("102", "李千问");
        assertEquals("SUCCESS", memory.findAudits("102").get(0).result());
        assertTrue(memory.findAudits("103").isEmpty());
        assertTrue(new MemoryBookingRepository().findAudits(null).isEmpty());
    }
    @Test void auditListingIsBoundedAndFiltered() {
        for (int i = 0; i < 105; i++) service.cancelBooking("102", "李千问");
        assertEquals(100, repository.findAudits(null).size());
        assertTrue(repository.findAudits("103").isEmpty());
    }
}
