package ai.spring.demo.ai.playground.auth;

import ai.spring.demo.ai.playground.repositories.JdbcBookingRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MigrationCompatibilityTest {
    private DriverManagerDataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:migration_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    }
    @Test void adoptsNonEmptyLegacySchemaWithoutClaimingOrChangingOrders() {
        var data=database(); var jdbc=new JdbcTemplate(data);
        new JdbcBookingRepository(jdbc,new DataSourceTransactionManager(data)).initialize();
        jdbc.update("UPDATE flight_bookings SET departure='深圳',destination='上海',flight_date='2026-11-20' WHERE booking_number='101'");
        jdbc.update("INSERT INTO booking_audit(booking_number,action,result,message,occurred_at) VALUES ('101','CHANGE','SUCCESS','历史审计',CURRENT_TIMESTAMP)");
        var original=jdbc.queryForList("SELECT * FROM flight_bookings ORDER BY booking_number");
        var flyway=Flyway.configure().dataSource(data).baselineOnMigrate(true).baselineVersion("0").load();
        flyway.migrate(); flyway.migrate();
        assertEquals(original,jdbc.queryForList("SELECT * FROM flight_bookings ORDER BY booking_number"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_ownership",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking_audit WHERE message='历史审计' AND actor_user_id IS NULL",Integer.class));
    }
    @Test void bootstrapCreatesOneAdministratorAndNeverResetsItsPassword() {
        var data=database(); Flyway.configure().dataSource(data).load().migrate();
        var jdbc=new JdbcTemplate(data); var encoder=new BCryptPasswordEncoder(4); var accounts=new AccountService(jdbc,encoder);
        assertThrows(IllegalStateException.class,() -> accounts.bootstrap("admin",""));
        accounts.bootstrap("admin","first-local-password-123");
        accounts.bootstrap("admin","another-local-password-456");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE role='ADMIN'",Integer.class));
        assertTrue(encoder.matches("first-local-password-123",accounts.load("admin").getPassword()));
        assertFalse(encoder.matches("another-local-password-456",accounts.load("admin").getPassword()));
    }
}
