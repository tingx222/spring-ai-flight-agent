package ai.spring.demo.ai.playground.services;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.regex.Pattern;

/** 无年份时按当前年份理解；不自动滚到下一年，过去日期由业务层拒绝。 */
public final class FlightDateParser {
    private static final Pattern FULL = Pattern.compile("([0-9]{4})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})日?");
    private static final Pattern SHORT = Pattern.compile("([0-9]{1,2})[./月-]([0-9]{1,2})日?");
    private FlightDateParser() {}
    public static LocalDate parse(String value, LocalDate today) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请告诉我新的出发日期，例如10月20日。");
        value = java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC);
        if (value.equals("今天")) return today;
        if (value.equals("明天")) return today.plusDays(1);
        if (value.equals("后天")) return today.plusDays(2);
        var full = FULL.matcher(value);
        var shortDate = SHORT.matcher(value);
        try {
            if (full.matches()) return LocalDate.of(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3)));
            if (shortDate.matches()) return LocalDate.of(today.getYear(), Integer.parseInt(shortDate.group(1)), Integer.parseInt(shortDate.group(2)));
        }
        catch (DateTimeException e) { throw new IllegalArgumentException("这个日期不存在，请核对月份和日期。"); }
        throw new IllegalArgumentException("没有识别出具体日期，请告诉我几月几日；可以说10.20、10月20日、明天，或提供完整年月日。");
    }
}
