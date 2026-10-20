package ai.spring.demo.ai.playground.services;

import java.time.LocalDate;
import java.util.function.BiFunction;
import org.springframework.ai.chat.model.ToolContext;
import ai.spring.demo.ai.playground.data.BookingStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

/**
 * AI 工具类。
 * 作用：把 Java 的业务接口包装成大模型（LLM）能看懂的“工具”。
 * 大模型本身无法直接连数据库，它通过调用这里的 @Bean 方法来查票、改签、退票。
 */
@Configuration
public class BookingTools {

    @Autowired
    private FlightBookingService flightBookingService;

    @Autowired
    private OperationConfirmationService confirmations;

    @Autowired(required = false)
    private ai.spring.demo.ai.playground.auth.BookingAccessService access;

    // ================== 定义 AI 传给工具的“请求参数格式” ==================

    public record BookingDetailsRequest(String bookingNumber, String name) {}

    // 注意 @JsonPropertyDescription：这不仅是给程序员看的，也是写给大模型看的，告诉它这两个参数怎么填。
    public record ChangeBookingDatesRequest(String bookingNumber, String name, String date,
                                            @com.fasterxml.jackson.annotation.JsonPropertyDescription("可省略。仅改日期时沿用原订单出发地，不可修改航线") String from,
                                            @com.fasterxml.jackson.annotation.JsonPropertyDescription("可省略。仅改日期时沿用原订单目的地，不可修改航线") String to) {}

    public record CancelBookingRequest(String bookingNumber, String name) {}

    // 返回给 AI 的数据格式（NON_NULL 表示如果字段是空的，就不传给 AI，省 Token）
    @JsonInclude(Include.NON_NULL)
    public record BookingDetails(String bookingNumber, String name, LocalDate date, BookingStatus bookingStatus,
                                 String from, String to, String bookingClass) {}

    public record BookingLookupResult(boolean success, String message, BookingDetails booking) {}

    // ================== 定义提供给 AI 的具体工具 ==================

    /**
     * 工具 1：查询订单详情
     * @Description 里的文字是写给 AI 看的系统提示词，AI 根据这段话决定什么时候调用这个工具。
     */
    @Bean
    @Description("获取机票预定详细信息，预订号必须由用户提供")
    public BiFunction<BookingDetailsRequest, ToolContext, BookingLookupResult> getBookingDetails() {
        return (request, context) -> {
            // 【安全防线】检查上下文里，用户是否真的提过这个订单号，防止 AI 瞎编单号去查别人的票。
            if (!BookingInputGuard.allows(request.bookingNumber(), context)) {
                return new BookingLookupResult(false, BookingInputGuard.MISSING, null);
            }
            try {
                var booking = access == null ? flightBookingService.getBookingDetails(request.bookingNumber(), request.name()) :
                    access.execute(access.identity(context), request.bookingNumber(),
                        () -> flightBookingService.getBookingDetails(request.bookingNumber(), request.name()));
                return new BookingLookupResult(true, "查询成功。", booking);
            }
            catch (IllegalArgumentException e) {
                // 如果查不到，返回友好的中文提示给 AI，不让底层报错直接崩溃。
                return new BookingLookupResult(false, "未找到与预订号和姓名匹配的订单，请核对您提供的信息。", null);
            }
        };
    }

    /**
     * 工具 2：生成改签预览（注意：绝对不直接执行！）
     */
    @Bean
    @Description("仅生成改签预览，不执行。预订号必须由用户提供。向用户展示预览并等待下一轮确认")
    public BiFunction<ChangeBookingDatesRequest, ToolContext, String> changeBooking() {
        return (request, context) -> {
            if (!BookingInputGuard.allows(request.bookingNumber(), context)) return BookingInputGuard.MISSING;
            try {
                // 调用底层服务生成预览方案，存入缓存。等用户下一轮说“确认”时才真正执行。
                return confirmations.preview("CHANGE", request.bookingNumber(), request.name(), request.date(), request.from(), request.to(), context);
            }
            catch (IllegalArgumentException e) {
                return "改签未执行：" + e.getMessage();
            }
        };
    }

    /**
     * 工具 3：生成取消预览（同样不直接执行）
     */
    @Bean
    @Description("仅生成取消预览，不执行。预订号必须由用户提供。向用户展示预览并等待下一轮确认")
    public BiFunction<CancelBookingRequest, ToolContext, String> cancelBooking() {
        return (request, context) -> {
            if (!BookingInputGuard.allows(request.bookingNumber(), context)) return BookingInputGuard.MISSING;
            try {
                return confirmations.preview("CANCEL", request.bookingNumber(), request.name(), null, null, null, context);
            }
            catch (IllegalArgumentException e) {
                return "取消未执行：" + e.getMessage();
            }
        };
    }
}