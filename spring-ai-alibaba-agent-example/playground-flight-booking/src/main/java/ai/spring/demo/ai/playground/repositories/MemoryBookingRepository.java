package ai.spring.demo.ai.playground.repositories;
import ai.spring.demo.ai.playground.data.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!mysql")
public class MemoryBookingRepository implements BookingRepository {
    private final List<Booking> bookings = DemoBookings.create();
    private final java.util.ArrayList<BookingAuditRecord> audits = new java.util.ArrayList<>();
    // 本机内存演示串行处理操作；重启后审计和订单都重置。
    public synchronized <T> T inTransaction(java.util.function.Supplier<T> work) { return work.get(); }
    public synchronized void appendAudit(BookingAuditRecord audit) { audits.add(audit); }
    public synchronized List<BookingAuditRecord> findAudits(String number) {
        var result = new java.util.ArrayList<BookingAuditRecord>();
        for (int i = audits.size() - 1; i >= 0 && result.size() < 100; i--) {
            var audit = audits.get(i);
            if (number == null || number.equals(audit.bookingNumber())) result.add(audit);
        }
        return List.copyOf(result);
    }
    public List<Booking> findAll() { return bookings; }
    public Optional<Booking> find(String number, String name) {
        return bookings.stream().filter(b -> b.getBookingNumber().equalsIgnoreCase(number)
            && b.getCustomer().getName().equalsIgnoreCase(name)).findFirst();
    }
    public void change(String number, String name, LocalDate date, String from, String to) {
        Booking booking = find(number, name).orElseThrow(() -> new IllegalArgumentException("Booking not found"));
        booking.setDate(date);
    }
    public void cancel(String number, String name) {
        find(number, name).orElseThrow(() -> new IllegalArgumentException("Booking not found"))
            .setBookingStatus(BookingStatus.CANCELLED);
    }
}
