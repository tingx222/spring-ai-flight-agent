package ai.spring.demo.ai.playground.auth;

import ai.spring.demo.ai.playground.data.BookingAuditRecord;
import ai.spring.demo.ai.playground.data.BookingStatus;
import ai.spring.demo.ai.playground.services.BookingTools.BookingDetails;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("secure")
public class BookingAccessService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuditActor actor;
    public BookingAccessService(JdbcTemplate jdbc,PlatformTransactionManager manager,AuditActor actor) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager); this.actor=actor;
    }
    public UserIdentity identity(ToolContext context) {
        if (context==null || !(context.getContext().get(UserIdentity.TOOL_CONTEXT_KEY) instanceof UserIdentity identity))
            throw new IllegalArgumentException("请先登录，不能通过聊天提供身份认证。");
        return identity;
    }
    public void require(UserIdentity identity,String number) {
        if (identity==null) throw new IllegalArgumentException("请先登录。");
        Long count=identity.admin() ? jdbc.queryForObject("SELECT COUNT(*) FROM flight_bookings WHERE booking_number=?",Long.class,number) :
            jdbc.queryForObject("SELECT COUNT(*) FROM booking_ownership WHERE booking_number=? AND user_id=?",Long.class,number,identity.userId());
        if (count==null || count==0) throw new IllegalArgumentException("订单不存在或不属于当前登录账号。");
    }
    /** Ownership reassignment takes the same flight row lock, so authorization and mutation are atomic. */
    public <T> T execute(UserIdentity identity,String number,Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.queryForList("SELECT booking_number FROM flight_bookings WHERE booking_number=? FOR UPDATE",number);
            require(identity,number);
            return actor.run(identity.userId(),work);
        });
    }
    public List<BookingDetails> bookings(UserIdentity identity) {
        String sql="SELECT b.* FROM flight_bookings b" + (identity.admin() ? "" : " JOIN booking_ownership o ON b.booking_number=o.booking_number WHERE o.user_id=?") + " ORDER BY b.booking_number";
        return jdbc.query(sql,(rs,row) -> new BookingDetails(rs.getString("booking_number"),rs.getString("customer_name"),
            rs.getDate("flight_date").toLocalDate(),BookingStatus.valueOf(rs.getString("booking_status")),
            rs.getString("departure"),rs.getString("destination"),rs.getString("booking_class")),identity.admin()?new Object[]{}:new Object[]{identity.userId()});
    }
    public record AuditView(String bookingNumber,String action,String result,String message,java.time.Instant occurredAt,
                            BookingAuditRecord.Snapshot before,BookingAuditRecord.Snapshot after,Long actorUserId) {}
    public List<AuditView> audits(UserIdentity identity,String number) {
        if (number!=null) require(identity,number);
        String sql="SELECT a.* FROM booking_audit a";
        var args=new java.util.ArrayList<Object>();
        if (!identity.admin()) { sql+=" JOIN booking_ownership o ON a.booking_number=o.booking_number WHERE o.user_id=?"; args.add(identity.userId()); }
        else sql+=" WHERE 1=1";
        if (number!=null) { sql+=" AND a.booking_number=?"; args.add(number); }
        return jdbc.query(sql+" ORDER BY a.id DESC LIMIT 100",(rs,row) -> new AuditView(rs.getString("booking_number"),
            rs.getString("action"),rs.getString("result"),rs.getString("message"),rs.getTimestamp("occurred_at").toInstant(),
            BookingAuditRecord.decode(rs.getString("before_data")),BookingAuditRecord.decode(rs.getString("after_data")),
            rs.getObject("actor_user_id",Long.class)),args.toArray());
    }
    public record AssignmentView(String bookingNumber,String name,Long userId,String username) {}
    public List<AssignmentView> assignments(UserIdentity identity) {
        requireAdmin(identity);
        return jdbc.query("SELECT b.booking_number,b.customer_name,o.user_id,u.username FROM flight_bookings b LEFT JOIN booking_ownership o ON b.booking_number=o.booking_number LEFT JOIN app_users u ON o.user_id=u.id ORDER BY b.booking_number",
            (rs,row) -> new AssignmentView(rs.getString(1),rs.getString(2),rs.getObject(3,Long.class),rs.getString(4)));
    }
    public void assign(UserIdentity identity,String number,long userId) {
        requireAdmin(identity);
        transactions.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT booking_number FROM flight_bookings WHERE booking_number=? FOR UPDATE",number).isEmpty())
                throw new IllegalArgumentException("订单不存在。");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=? AND enabled=TRUE",Long.class,userId)==0)
                throw new IllegalArgumentException("目标用户不存在或已停用。");
            var old=jdbc.queryForList("SELECT user_id FROM booking_ownership WHERE booking_number=?",Long.class,number);
            if (!old.isEmpty() && old.get(0)==userId) return;
            if (old.isEmpty()) jdbc.update("INSERT INTO booking_ownership(booking_number,user_id) VALUES (?,?)",number,userId);
            else jdbc.update("UPDATE booking_ownership SET user_id=? WHERE booking_number=?",userId,number);
            jdbc.update("INSERT INTO ownership_audit(booking_number,actor_user_id,previous_user_id,new_user_id) VALUES (?,?,?,?)",number,identity.userId(),old.isEmpty()?null:old.get(0),userId);
        });
    }
    private static void requireAdmin(UserIdentity identity) {
        if (identity==null || !identity.admin()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
    }
}
