package ai.spring.demo.ai.playground.services;
import ai.spring.demo.ai.playground.services.BookingTools.BookingDetails;
import java.time.*;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 本机单实例会话方案；不向模型提供执行确认的工具。重启后必须重新预览。 */
@Service
public class OperationConfirmationService {
    public static final String CHAT = "confirmationChatId", TURN = "confirmationTurnId";
    private final FlightBookingService service;
    private final Clock clock;
    @Autowired(required = false)
    private ai.spring.demo.ai.playground.auth.BookingAccessService access;
    private final Map<String, Pending> pending = new HashMap<>();
    private static final Duration TTL = Duration.ofMinutes(5);
    private static class Pending {
        String turn, action, date, preview, reply;
        BookingDetails expected;
        ai.spring.demo.ai.playground.auth.UserIdentity identity;
        Instant expires;
        boolean shown, consumed;
    }
    @Autowired public OperationConfirmationService(FlightBookingService service) { this(service, Clock.systemUTC()); }
    OperationConfirmationService(FlightBookingService service, Clock clock) { this.service=service;this.clock=clock; }
    public static boolean isConfirmation(String text) {
        return text != null && text.trim().matches("(?:是|是的|确认|确认执行|确认改签|确认取消|确定|同意|好的|好|可以)[。！!\\s]*");
    }
    public static boolean isWithdrawal(String text) {
        return text != null && text.trim().matches("(?:算了|不改了|不取消了|不要了|不用了|撤销|撤销方案|取消操作)[。！!\\s]*");
    }
    public static boolean revisesPlan(String text) {
        return text != null && text.matches("(?s).*(改|换|取消|预订|预定|订单|[0-9０-９]|明天|后天|今天).*" );
    }
    public synchronized void invalidate(String chat) { pending.remove(chat); }
    public synchronized String preview(String action, String number, String name, String date, String from, String to, ToolContext context) {
        if (access == null) return previewAuthorized(action, number, name, date, from, to, context);
        var identity = access.identity(context);
        return access.execute(identity, number, () -> previewAuthorized(action, number, name, date, from, to, context));
    }
    private String previewAuthorized(String action, String number, String name, String date, String from, String to, ToolContext context) {
        // 新的方案尝试先撤销旧方案，即使新参数被业务校验拒绝也不能回退执行旧方案。
        if (context != null && context.getContext().get(CHAT) instanceof String previousChat) pending.remove(previousChat);
        var identity = access == null ? null : access.identity(context);
        if (access != null) access.require(identity, number);
        // 先做业务校验；被拒绝的请求不产生可确认方案。
        BookingDetails target = action.equals("CHANGE") ? service.previewChange(number,name,date,from,to) : service.previewCancel(number,name);
        if (action.equals("CANCEL") && target.bookingStatus() == ai.spring.demo.ai.playground.data.BookingStatus.CANCELLED) return "订单已取消，无需重复操作。";
        if (context == null || !(context.getContext().get(CHAT) instanceof String chat)
                || !(context.getContext().get(TURN) instanceof String turn)) return "缺少会话信息，未生成方案，订单未修改。";
        pending.entrySet().removeIf(e -> e.getValue().expires.plus(Duration.ofMinutes(10)).isBefore(clock.instant()));
        if (pending.size() >= 1000 && !pending.containsKey(chat)) return "待确认会话过多，请稍后重试，订单未修改。";
        Pending p = new Pending();p.turn=turn;p.action=action;p.date=target.date().toString();
        p.expected=service.getBookingDetails(number,name);p.expires=clock.instant().plus(TTL);
        int fee = switch (target.bookingClass()) {
            case "ECONOMY" -> action.equals("CHANGE") ? 50 : 75;
            case "PREMIUM_ECONOMY" -> action.equals("CHANGE") ? 30 : 50;
            case "BUSINESS" -> action.equals("CHANGE") ? 0 : 25;
            default -> throw new IllegalArgumentException("无法确定舱位费用，请核对订单。");
        };
        p.preview="请确认操作方案（5分钟内有效）：\n订单："+number+"，乘机人："+name+"\n航线："+p.expected.from()+"→"+p.expected.to()
            +"\n操作："+(action.equals("CHANGE") ? "改签，"+p.expected.date()+" → "+p.date : "取消，原出发日期"+p.expected.date())
            +"\n条款费用："+fee+"美元；本项目未实际扣费或退款。\n订单尚未修改。回复“确认”或“是”执行；回复“算了”撤销。";
        p.identity=identity;
        pending.put(chat,p);return p.preview;
    }
    public synchronized boolean hasPreview(String chat,String turn) {
        Pending p=pending.get(chat);return p!=null && p.turn.equals(turn) && !p.consumed;
    }
    public synchronized String present(String chat,String turn) {
        if (!hasPreview(chat,turn)) return null;
        Pending p=pending.get(chat);p.shown=true;return p.preview;
    }
    public synchronized String confirm(String chat) { return confirm(chat, null); }
    public synchronized String confirm(String chat, ai.spring.demo.ai.playground.auth.UserIdentity identity) {
        Pending p=pending.get(chat);
        if (p==null) return "当前没有待确认的操作方案，请先告诉我需要改签或取消哪笔订单。";
        if (access != null && (identity == null || !identity.equals(p.identity))) return "当前登录身份与方案不匹配，请重新生成预览。";
        if (p.consumed) return "该方案已处理，无需重复确认。"+p.reply;
        if (!p.shown) return "方案尚未展示，请先查看完整预览再确认，订单未修改。";
        if (!clock.instant().isBefore(p.expires)) { pending.remove(chat);return "方案已过期，订单未修改。请重新生成预览。"; }
        p.consumed=true;
        try {
            p.reply=access == null ? service.executeConfirmed(p.expected,p.action,p.date) :
                access.execute(identity,p.expected.bookingNumber(),() -> service.executeConfirmed(p.expected,p.action,p.date));
        }
        catch (IllegalArgumentException e) { p.reply="未执行："+e.getMessage(); }
        catch (RuntimeException e) {
            p.reply="执行遇到系统异常。请先核对订单与审计结果，再重新生成方案；请勿重复确认旧方案。";
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Confirmed operation failed for order {}",p.expected.bookingNumber(),e);
        }
        return p.reply;
    }
}
