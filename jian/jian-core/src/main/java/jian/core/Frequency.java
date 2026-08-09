package jian.core;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.*;

// ┌─ What : Frequency —— pandas 风格时间频率解析与运算(对齐 pandas.tseries.offsets)
// │  Why  : resample("1D")/shift("2H")/asfreq("1W") 需要把频率字符串解析为可计算的频率对象
// │  Who  : 由 DatetimeIndex / Resampler / DataFrame.shift(freq) 使用
// │  When : resample/asfreq/shift(freq)/频率推断
// │  Where: jian-core/Frequency.java
// │  How  : 数据走向:频率字符串("1D"/"2H"/"1W"/"ME"/"YS")→ 解析为 (amount, unit) 元组。
// │         关键变量变化:
// │           - amount:long 数量(1D 的 1,2H 的 2)
// │           - unit:ChronoUnit(DAYS/HOURS/MINUTES/SECONDS/WEEKS/MONTHS/YEARS)
// │         逻辑路线:
// │           路径 A(简单频率 "1D"/"2H")→ 正则匹配 amount + 单位字母 → ChronoUnit
// │           路径 B(月/年级频率 "ME"/"YS")→ 月末/年初语义;按 Month/YEAR 加法实现
// │         不变量:amount >= 1;unit 非 null。
/**
 * pandas 风格时间频率,对齐 pandas.tseries.offsets。
 *
 * <p>支持解析的频率字符串(大小写敏感):
 * <ul>
 *   <li><b>子日级</b>:`"Ns"`(秒)、`"Nmin"`(分)、`"Nh"`(小时)</li>
 *   <li><b>日级</b>:`"ND"`(天)、`"NW"`(周)</li>
 *   <li><b>月级</b>:`"NM"`(月)、`"NME"`(月末)、`"NMS"`(月初)</li>
 *   <li><b>季级</b>:`"NQ"`(季)、`"NQE"`(季末)</li>
 *   <li><b>年级</b>:`"NY"`(年)、`"NYS"`(年初)、`"NYE"`(年末)</li>
 * </ul>
 * 其中 N 为正整数,可省略(默认 1),如 `"D"` == `"1D"`。
 *
 * <p><b>不可变</b>。
 *
 * <p>用法:
 * <pre>{@code
 * Frequency f = Frequency.parse("1D");
 * LocalDateTime next = f.plus(LocalDateTime.of(2026,1,1,0,0));  // 2026-01-02
 * LocalDateTime range = f.rangeStart(start, 10);  // 起点 + 10 个频率
 * }</pre>
 */
public final class Frequency {

    /** 单位(对齐 java.time.temporal.ChronoUnit 子集)。 */
    public enum Unit {
        SECONDS(ChronoUnit.SECONDS),
        MINUTES(ChronoUnit.MINUTES),
        HOURS(ChronoUnit.HOURS),
        DAYS(ChronoUnit.DAYS),
        WEEKS(ChronoUnit.WEEKS),
        MONTHS(ChronoUnit.MONTHS),
        QUARTERS(null),   // 季 = 3 个月,特殊处理
        YEARS(ChronoUnit.YEARS);

        final ChronoUnit chrono;
        Unit(ChronoUnit c) { this.chrono = c; }
    }

    private static final Pattern PATTERN = Pattern.compile(
        "^(\\d+)?\\s*(s|min|h|D|W|M|ME|MS|Q|QE|Y|YS|YE)$", Pattern.CASE_INSENSITIVE);

    private final long amount;     // 数量,≥ 1
    private final Unit unit;
    private final String original; // 原始字符串(用于 toString)

    private Frequency(long amount, Unit unit, String original) {
        if (amount < 1) throw new IllegalArgumentException("Frequency 数量必须 ≥ 1:" + amount);
        this.amount = amount;
        this.unit = Objects.requireNonNull(unit);
        this.original = original;
    }

    /**
     * 解析频率字符串(大小写不敏感,对齐 pandas)。
     * @param spec String 频率字符串("1D"/"2H"/"1W"/"ME"/"YS"/"12h"/"3d" 均合法);非 null
     * @return Frequency 解析后的对象
     * @throws IllegalArgumentException 字符串格式不合法 或 不支持的频率单位
     */
    public static Frequency parse(String spec) {
        Objects.requireNonNull(spec, "频率字符串不能为 null");
        Matcher m = PATTERN.matcher(spec.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("不支持的频率格式:" + spec
                + "(支持:Ns/Nmin/Nh/ND/NW/NM/NME/NMS/NQ/NQE/NY/NYS/NYE,N 省略=1,大小写不敏感)");
        }
        long amount = m.group(1) == null ? 1 : Long.parseLong(m.group(1));
        String unitStr = m.group(2).toLowerCase();  // 统一小写匹配
        Unit unit = switch (unitStr) {
            case "s" -> Unit.SECONDS;
            case "min" -> Unit.MINUTES;
            case "h" -> Unit.HOURS;
            case "d" -> Unit.DAYS;
            case "w" -> Unit.WEEKS;
            case "m", "ms", "me" -> Unit.MONTHS;
            case "q", "qe" -> Unit.QUARTERS;
            case "y", "ys", "ye" -> Unit.YEARS;
            default -> throw new IllegalArgumentException("未知频率单位:" + unitStr);
        };
        return new Frequency(amount, unit, spec.trim());
    }

    /**
     * @return long 数量,≥ 1
     */
    public long amount() { return amount; }

    /**
     * @return Unit 单位
     */
    public Unit unit() { return unit; }

    /**
     * 在时间点 t 上加一个频率步。
     * @param t LocalDateTime 时间点,非 null
     * @return LocalDateTime t + amount × unit
     */
    public LocalDateTime plus(LocalDateTime t) {
        Objects.requireNonNull(t);
        return switch (unit) {
            case SECONDS -> t.plusSeconds(amount);
            case MINUTES -> t.plusMinutes(amount);
            case HOURS -> t.plusHours(amount);
            case DAYS -> t.plusDays(amount);
            case WEEKS -> t.plusWeeks(amount);
            case MONTHS -> t.plusMonths(amount);
            case QUARTERS -> t.plusMonths(amount * 3);
            case YEARS -> t.plusYears(amount);
        };
    }

    /**
     * 在时间点 t 上减一个频率步。
     * @param t LocalDateTime 时间点,非 null
     * @return LocalDateTime t - amount × unit
     */
    public LocalDateTime minus(LocalDateTime t) {
        Objects.requireNonNull(t);
        return switch (unit) {
            case SECONDS -> t.minusSeconds(amount);
            case MINUTES -> t.minusMinutes(amount);
            case HOURS -> t.minusHours(amount);
            case DAYS -> t.minusDays(amount);
            case WEEKS -> t.minusWeeks(amount);
            case MONTHS -> t.minusMonths(amount);
            case QUARTERS -> t.minusMonths(amount * 3);
            case YEARS -> t.minusYears(amount);
        };
    }

    /**
     * 生成频率网格:从 start 起,共 count 个时间点,每个间隔一个频率步。
     * @param start LocalDateTime 起点,非 null
     * @param count int 时间点数,≥ 0
     * @return LocalDateTime[] 长度 count;count==0 返回空数组
     */
    public LocalDateTime[] range(LocalDateTime start, int count) {
        Objects.requireNonNull(start);
        LocalDateTime[] out = new LocalDateTime[count];
        LocalDateTime cur = start;
        for (int i = 0; i < count; i++) {
            out[i] = cur;
            cur = plus(cur);
        }
        return out;
    }

    /**
     * 计算两个时间点之间的频率步数(floor,向下取整)。
     * @param from LocalDateTime 起点,非 null
     * @param to   LocalDateTime 终点,非 null
     * @return long 步数(from 到 to 之间有多少个完整频率步,负值表示 to < from)
     */
    public long stepsBetween(LocalDateTime from, LocalDateTime to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        return switch (unit) {
            case SECONDS -> from.until(to, ChronoUnit.SECONDS) / amount;
            case MINUTES -> from.until(to, ChronoUnit.MINUTES) / amount;
            case HOURS -> from.until(to, ChronoUnit.HOURS) / amount;
            case DAYS -> from.until(to, ChronoUnit.DAYS) / amount;
            case WEEKS -> from.until(to, ChronoUnit.WEEKS) / amount;
            case MONTHS -> from.until(to, ChronoUnit.MONTHS) / amount;
            case QUARTERS -> from.until(to, ChronoUnit.MONTHS) / (amount * 3);
            case YEARS -> from.until(to, ChronoUnit.YEARS) / amount;
        };
    }

    /**
     * 转回频率字符串。
     * @return String 形如 "1D"/"2H";若原始解析串存在则优先返回原始
     */
    @Override public String toString() { return original; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Frequency f)) return false;
        return amount == f.amount && unit == f.unit;
    }

    @Override public int hashCode() { return Objects.hash(amount, unit); }
}
