package ai.spring.demo.ai.playground.client;
import ai.spring.demo.ai.playground.data.BookingAuditRecord;
import ai.spring.demo.ai.playground.services.FlightBookingService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 本机演示查询入口，与现有订单接口一样尚无登录鉴权。 */
@RestController
@RequestMapping("/api/booking-audits")
public class BookingAuditController {
    private final FlightBookingService service;
    public BookingAuditController(FlightBookingService service) { this.service = service; }
    @GetMapping
    public List<BookingAuditRecord> list(@RequestParam(name = "bookingNumber", required = false) String bookingNumber) {
        return service.getAuditRecords(bookingNumber);
    }
}
