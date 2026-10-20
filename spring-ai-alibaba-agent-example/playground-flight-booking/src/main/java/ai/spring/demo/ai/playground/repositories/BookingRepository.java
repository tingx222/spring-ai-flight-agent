package ai.spring.demo.ai.playground.repositories;

import ai.spring.demo.ai.playground.data.Booking;
import ai.spring.demo.ai.playground.data.BookingAuditRecord;
import java.util.function.Supplier;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookingRepository {

    /**
     * 【事务管理：打包执行】
     * 作用：把一段包含多个数据库操作的代码块（work）打包，确保它们“同生共死”。
     *
     * 如果 work.get() 里面的代码全部执行成功，数据库会提交（Commit）；
     * 如果 work.get() 里面抛出任何异常，数据库会全部回滚（Rollback），撤销之前的修改。
     *
     * 为什么这里写 default 方法？
     * 1. 内存版数据库（MemoryBookingRepository）没有复杂的事务，直接执行 work.get() 即可。
     * 2. JDBC 版数据库（JdbcBookingRepository）会“重写”这个方法，加上 @Transactional 注解，交给真实数据库去管事务。
     *
     * @param work 你要执行的一坨代码（比如：先查订单，再改订单，再记审计日志）
     * @return 代码执行完后的返回值
     */
    default <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }

    /**
     * 追加一条审计日志。
     * 记录“谁在什么时候，做了什么操作，结果是成功、被拒绝还是无操作”。
     */
    void appendAudit(BookingAuditRecord audit);

    /**
     * 根据订单号查询该订单的所有审计历史记录。
     */
    List<BookingAuditRecord> findAudits(String bookingNumber);

    /**
     * 查询系统里所有的订单列表（通常用于后台管理或 AI 获取全局上下文）。
     */
    List<Booking> findAll();

    /**
     * 精确查询某一个订单。
     *
     * 为什么要同时传“订单号 + 客户姓名”？
     * 为了防止越权访问（IDOR漏洞）。如果只传订单号，张三猜到了李四的订单号就能看到李四的行程。
     *
     * 为什么返回 Optional<Booking> 而不是 Booking？
     * 因为可能查不到（比如单号输错了）。用 Optional 包装，强制调用方处理“查不到”的情况，避免空指针异常（NullPointerException）。
     */
    Optional<Booking> find(String bookingNumber, String name);

    /**
     * 执行改签操作（把新的日期、航线写入数据库）。
     */
    void change(String bookingNumber, String name, LocalDate date, String from, String to);

    /**
     * 执行取消订单操作（把订单状态更新为 CANCELLED）。
     */
    void cancel(String bookingNumber, String name);
}