package ai.spring.demo.ai.playground.data;

import java.time.Instant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 记录业务结果，姓名不是已认证操作者；审计不存密钥或完整聊天。 */
public record BookingAuditRecord(String bookingNumber, String action, String result, String message,
        Instant occurredAt, Snapshot before, Snapshot after) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Snapshot(String date, String status, String departure, String destination, String bookingClass) {}
    public static Snapshot snapshot(Booking booking) {
        return new Snapshot(booking.getDate().toString(), booking.getBookingStatus().name(),
            booking.getFrom(), booking.getTo(), booking.getBookingClass().name());
    }
    public static String encode(Snapshot snapshot) {
        if (snapshot == null) return null;
        try { return JSON.writeValueAsString(snapshot); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize audit snapshot", e); }
    }
    public static Snapshot decode(String json) {
        if (json == null) return null;
        try { return JSON.readValue(json, Snapshot.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read audit snapshot", e); }
    }
}
