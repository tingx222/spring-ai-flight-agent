package ai.spring.demo.ai.playground.repositories;

import ai.spring.demo.ai.playground.data.*;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL 数据库版的订单存储实现。
 * 只有在启动参数激活了 "mysql" 环境（--spring.profiles.active=mysql）时才会生效。
 */
@Repository
@Profile("mysql")
public class JdbcBookingRepository implements BookingRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * 核心工具：将数据库查出来的 ResultSet（一行行数据），映射转换成 Java 的 Booking 对象。
     */
    private static final RowMapper<Booking> MAPPER = (rs, row) -> {
        Customer customer = new Customer();
        customer.setName(rs.getString("customer_name"));
        return new Booking(rs.getString("booking_number"), rs.getDate("flight_date").toLocalDate(),
                customer, BookingStatus.valueOf(rs.getString("booking_status")),
                rs.getString("departure"), rs.getString("destination"),
                BookingClass.valueOf(rs.getString("booking_class")));
    };

    public JdbcBookingRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * 【启动初始化】当 Spring 启动并加载这个类时，自动执行。
     * 1. 建表（如果表不存在的话，IF NOT EXISTS）。
     * 2. 如果订单表是空的，塞入假数据。
     * 3. 修复可能损坏的数据。
     */
    @PostConstruct
    public void initialize() {
        // 建订单主表
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS flight_bookings (
                booking_number VARCHAR(32) PRIMARY KEY,
                customer_name VARCHAR(100) NOT NULL,
                flight_date DATE NOT NULL,
                booking_status VARCHAR(32) NOT NULL,
                departure VARCHAR(100) NOT NULL,
                destination VARCHAR(100) NOT NULL,
                booking_class VARCHAR(32) NOT NULL
            )
            """);
        // 建审计日志表
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS booking_audit (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                booking_number VARCHAR(255),
                action VARCHAR(16) NOT NULL,
                result VARCHAR(16) NOT NULL,
                message VARCHAR(1000) NOT NULL,
                occurred_at TIMESTAMP(6) NOT NULL,
                before_data TEXT,
                after_data TEXT
            )
            """);

        // 开启一个事务去执行初始化和数据修复
        transactions.executeWithoutResult(status -> {
            // 只有当订单表里没有任何数据时，才插入假数据（防止每次重启数据翻倍）
            if (jdbc.queryForObject("SELECT COUNT(*) FROM flight_bookings", Long.class) == 0) {
                for (Booking b : DemoBookings.create()) {
                    jdbc.update("INSERT INTO flight_bookings VALUES (?, ?, ?, ?, ?, ?, ?)",
                            b.getBookingNumber(), b.getCustomer().getName(), b.getDate(),
                            b.getBookingStatus().name(), b.getFrom(), b.getTo(), b.getBookingClass().name());
                }
            }
            // 修复可能丢失航线的坏数据
            repairMissingRoutesFromAudit();
        });
    }

    /**
     * 【高级兜底逻辑】从历史审计记录中，找回丢失的航线数据。
     * 如果数据库里的订单出发地或目的地为空，就去审计表里翻旧账，找到之前的航线恢复回来。
     */
    private void repairMissingRoutesFromAudit() {
        // 找出航线缺失的订单，并加上 FOR UPDATE 锁，防止修复时被别人修改
        var damaged = jdbc.query("SELECT * FROM flight_bookings WHERE TRIM(departure) = '' OR TRIM(destination) = '' ORDER BY booking_number FOR UPDATE", MAPPER);
        for (Booking booking : damaged) {
            BookingAuditRecord.Snapshot source = null;
            // 遍历这个订单的历史审计记录
            for (BookingAuditRecord audit : findAudits(booking.getBookingNumber())) {
                // 找操作前或操作后包含完整航线的快照
                for (var candidate : new BookingAuditRecord.Snapshot[] {audit.after(), audit.before()}) {
                    if (candidate != null && candidate.departure() != null && !candidate.departure().isBlank()
                            && candidate.destination() != null && !candidate.destination().isBlank()
                            && (booking.getFrom().isBlank() || booking.getFrom().equals(candidate.departure()))
                            && (booking.getTo().isBlank() || booking.getTo().equals(candidate.destination()))) {
                        source = candidate;
                        break;
                    }
                }
                if (source != null) break;
            }
            if (source == null) {
                // 如果审计记录里也找不到，打个警告日志，等待人工介入
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order {} has missing route with no matching audit snapshot; manual recovery required", booking.getBookingNumber());
                continue;
            }
            // 记录修复前的状态
            var before = BookingAuditRecord.snapshot(booking);
            // 更新数据库，把航线更新回来
            jdbc.update("UPDATE flight_bookings SET departure = ?, destination = ? WHERE booking_number = ?", source.departure(), source.destination(), booking.getBookingNumber());
            booking.setFrom(source.departure());
            booking.setTo(source.destination());
            // 写一条 "REPAIR" (修复) 的审计日志
            appendAudit(new BookingAuditRecord(booking.getBookingNumber(), "REPAIR", "SUCCESS",
                    "从可信审计快照恢复缺失航线，保留现有日期和状态。", java.time.Instant.now(), before, BookingAuditRecord.snapshot(booking)));
        }
    }

    /**
     * 查询所有订单。
     */
    public List<Booking> findAll() {
        return jdbc.query("SELECT * FROM flight_bookings ORDER BY booking_number", MAPPER);
    }

    /**
     * 【核心锁机制】查询单个订单。
     * 重点看这里的 `lock` 变量：
     * 如果当前代码运行在一个事务中（isActualTransactionActive），它会在 SQL 末尾拼接 " FOR UPDATE"。
     * 这代表“悲观锁”，即：我查这条数据的时候，数据库直接把这一行锁住，别人想来退票或改签必须排队等我搞完。
     */
    public Optional<Booking> find(String number, String name) {
        String lock = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                ? " FOR UPDATE" : "";
        return jdbc.query("SELECT * FROM flight_bookings WHERE booking_number = ? AND customer_name = ?" + lock,
                MAPPER, number, name).stream().findFirst();
    }

    /**
     * 改签。
     * 注意 SQL：只更新了日期（flight_date），根本没有传航线的参数。
     * 这是因为业务层规定“不能改航线”，所以在底层干脆就不给你留改航线的后门。
     */
    public void change(String number, String name, LocalDate date, String from, String to) {
        jdbc.update("UPDATE flight_bookings SET flight_date = ? WHERE booking_number = ? AND customer_name = ?",
                date, number, name);
    }

    /**
     * 取消订单：把状态更新为 CANCELLED。
     */
    public void cancel(String number, String name) {
        jdbc.update("UPDATE flight_bookings SET booking_status = ? WHERE booking_number = ? AND customer_name = ?",
                BookingStatus.CANCELLED.name(), number, name);
    }

    /**
     * 开启真实数据库事务。
     * 在这里面执行的代码，如果抛出异常，所有 SQL 都会自动回滚。
     */
    public <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /**
     * 写入审计日志（记录谁在什么时候干了什么，操作前长什么样，操作后长什么样）。
     */
    public void appendAudit(BookingAuditRecord audit) {
        jdbc.update("INSERT INTO booking_audit (booking_number, action, result, message, occurred_at, before_data, after_data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                audit.bookingNumber(), audit.action(), audit.result(), audit.message(), java.sql.Timestamp.from(audit.occurredAt()),
                BookingAuditRecord.encode(audit.before()), BookingAuditRecord.encode(audit.after()));
    }

    /**
     * 查询审计日志：按时间倒序（最新的在最前面），最多查 100 条。
     */
    public List<BookingAuditRecord> findAudits(String number) {
        RowMapper<BookingAuditRecord> mapper = (rs, row) -> new BookingAuditRecord(rs.getString("booking_number"),
                rs.getString("action"), rs.getString("result"), rs.getString("message"), rs.getTimestamp("occurred_at").toInstant(),
                BookingAuditRecord.decode(rs.getString("before_data")), BookingAuditRecord.decode(rs.getString("after_data")));
        if (number == null) return jdbc.query("SELECT * FROM booking_audit ORDER BY id DESC LIMIT 100", mapper);
        return jdbc.query("SELECT * FROM booking_audit WHERE booking_number = ? ORDER BY id DESC LIMIT 100", mapper, number);
    }
}