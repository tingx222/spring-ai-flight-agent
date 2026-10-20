package ai.spring.demo.ai.playground.services;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 【安全防线：订单输入守卫】
 * 核心原则：仅信任用户（USER）的消息，绝不从大模型（ASSISTANT）、RAG 知识库或工具结果中提取预订号。
 * 作用：防止 AI 幻觉，防止越权查询，确保工具调用的参数有据可查。
 */
public final class BookingInputGuard {

    // 存放在 ToolContext 里的 Key，代表了当前会话中“用户合法提供过的所有订单号集合”
    public static final String CONTEXT_KEY = "userProvidedBookingNumbers";

    // 当用户试图操作订单但没提供单号时，系统直接返回的标准追问话术
    public static final String MISSING = "请提供预订号，我会接着处理刚才的请求；如果还没提供乘机人姓名，也请一起告诉我。";

    // 正则 1：匹配带有标签的订单号，比如“预订号是 102”、“订单号：ABC-123”
    private static final Pattern LABELED = Pattern.compile("(?:预[订定](?:单)?(?:编号|号)?|订单(?:编号|号)?|订票号)\\s*(?:是|为|[:：])?\\s*([A-Za-z0-9][A-Za-z0-9-]*)");

    // 正则 2：匹配裸数字订单号，比如“101，云小宝，改签”中的 101。
    // 注意排除了日期（10.20）、金额（100元）、时长（5天）等数字，防止误判。
    private static final Pattern INLINE = Pattern.compile("(?<![A-Za-z0-9./:-])([0-9]{3,12})(?![A-Za-z0-9./:-]|\\s*(?:美元|元|块|小时|天|分钟|人|岁|年|月|日))");

    private BookingInputGuard() {}

    /**
     * 从聊天历史记录和当前这句话中，收集用户合法提供的订单号。
     * 只要最新的一句话里提了单号，就覆盖之前的旧单号。
     */
    public static Set<String> collect(List<Message> history, String current) {
        Set<String> active = Set.of();
        for (Message message : history) {
            // 划重点：只有 MessageType.USER 才被信任。大模型的回复一律不信。
            if (message.getMessageType() == MessageType.USER) {
                Set<String> supplied = extract(message.getText());
                if (!supplied.isEmpty()) active = supplied;
            }
        }
        Set<String> supplied = extract(current);
        return supplied.isEmpty() ? active : supplied;
    }

    /**
     * 底层正则提取引擎：从一段文本中抠出订单号。
     */
    private static Set<String> extract(String text) {
        if (text == null) return Set.of();
        // NFKC 规范化：把全角字符转半角（比如把全角的１２３转成123），方便正则匹配
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        Set<String> numbers = new HashSet<>();

        // 1. 优先找带标签的："预订号 102"
        var matcher = LABELED.matcher(text);
        while (matcher.find()) {
            String number = matcher.group(1);
            // 避免“预订2026-10-20”这种日期被误当作编号
            if (!number.matches("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}")) numbers.add(number.toUpperCase(java.util.Locale.ROOT));
        }

        // 2. 其次找裸数字："101"
        matcher = INLINE.matcher(text);
        while (matcher.find()) numbers.add(matcher.group(1));
        return Set.copyOf(numbers);
    }

    /**
     * 从用户原话中提取“姓名”提示。
     * 同样，只从用户消息里找，绝不从大模型输出里猜。
     */
    public static String providedName(List<Message> history, String current) {
        String name = "";
        var texts = new java.util.ArrayList<String>();
        for (Message message : history) if (message.getMessageType() == MessageType.USER) texts.add(message.getText());
        texts.add(current);

        // 匹配“我叫张三”、“我是李四”、“姓名：王五”
        var labeled = Pattern.compile("(?:我叫|我是|姓名[:：]?)\\s*([\\p{IsHan}·]{2,12})(?=[,，\\s]|$)");
        // 匹配“张三,101”这种姓名+单号组合
        var pair = Pattern.compile("^\\s*([\\p{IsHan}·]{2,12})\\s*[,，]\\s*[0-9]{3,12}(?=[,，\\s]|$)");

        for (String text : texts) {
            if (text == null) continue;
            var matcher = labeled.matcher(text);
            if (matcher.find()) name = matcher.group(1);
            else {
                matcher = pair.matcher(text);
                if (matcher.find()) name = matcher.group(1);
            }
        }
        return name;
    }

    /**
     * 【核心安检】当大模型决定调用工具时，校验它想查的订单号，是不是用户刚才亲自提过的。
     * 这个方法是 BookingTools 里所有工具的第一道防线。
     */
    public static boolean allows(String number, ToolContext context) {
        if (number == null || context == null) return false;
        // 从上下文里拿出我们之前提取好的白名单
        Object allowed = context.getContext().get(CONTEXT_KEY);
        // 如果白名单里包含这个订单号，才允许放行
        return allowed instanceof Set<?> values && values.contains(number.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 判断用户当前的意图，是否属于“需要订单号”的操作（比如改签、取消、查询）。
     */
    public static boolean needsOrder(String text) {
        // 如果用户问的是“退票规定是什么”，属于知识库问题，不需要订单号，走 RAG。
        if (text == null || text.matches("(?s).*(规定|条款|政策|手续费|提前多久|如何|怎么|能否|可以吗).*")) return false;
        // 如果用户说“我要退票”、“帮我查一下”，说明需要订单号才能继续。
        return text.matches("(?s).*(取消|改签|查询|查看|查一下|查查).*?");
    }
}