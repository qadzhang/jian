package jian.core;

import java.time.*;
import java.util.*;

// ┌─ What : DatetimeIndex —— 时间序列行标签(对齐 pandas.DatetimeIndex)
// │  Why  : resample/asfreq/shift(freq)/at_time/between_time 等时序算子依赖时间行索引
// │  Who  : 由 DataFrame.setIndexDatetime(col) 或 DatetimeIndex.of(...) 创建
// │  When : 时间序列分析(resample 重采样、asof 最近查询、at_time 时点筛选)
// │  Where: jian-core/DatetimeIndex.java
// │  How  : 数据走向:LocalDateTime[] + 频率字符串(可选)。
// │         关键变量变化:
// │           - instants:LocalDateTime[](必须升序;允许 null=缺失时间点)
// │           - freq:String 频率("1D"/"2H"/"1W"/null);null 表示频率未知
// │         逻辑路线:
// │           路径 A(已知频率)→ resample/asfreq 用 freq 生成目标网格
// │           路径 B(未知频率)→ 推断(inferFreq)或退化到按行处理
// │         不变量:instants 长度 == DataFrame 行数;若声明了 freq,必须升序。
/**
 * 时间序列行标签,对齐 pandas.DatetimeIndex。
 *
 * <p>用于支持时间序列算子(resample / asfreq / shift(freq) / at_time / between_time / asof)。
 *
 * <p><b>不可变</b>:变换返回新 DatetimeIndex。
 *
 * <p>用法:
 * <pre>{@code
 * DatetimeIndex di = DatetimeIndex.of(
 *     new LocalDateTime[]{
 *         LocalDateTime.of(2026, 1, 1, 0, 0),
 *         LocalDateTime.of(2026, 1, 2, 0, 0),
 *         LocalDateTime.of(2026, 1, 3, 0, 0)},
 *     "1D");  // 已知频率:每天
 * DataFrame df2 = df.setIndexDatetime("ts");
 * }</pre>
 */
public final class DatetimeIndex {

    private final LocalDateTime[] instants;
    private final String freq;          // 频率字符串,如 "1D"/"2H";null 表示未知
    private final String name;          // 索引名(可 null)

    /**
     * 公开构造(默认拷贝)。
     * @param instants LocalDateTime[] 时间点数组,非 null;允许元素 null(缺失);若 freq != null 必须升序
     * @param freq     String 频率("1D"/"2H"/"1W"/null);null 表示未知频率
     * @param name     String 索引名,可 null
     * @throws IllegalArgumentException instants 为空 或 freq!=null 但 instants 非升序
     */
    public DatetimeIndex(LocalDateTime[] instants, String freq, String name) {
        Objects.requireNonNull(instants, "instants 不能为 null");
        this.instants = instants.clone();
        if (freq != null && instants.length > 1) {
            // 校验升序(跳过 null)
            LocalDateTime prev = null;
            for (LocalDateTime cur : instants) {
                if (cur != null && prev != null && cur.isBefore(prev)) {
                    throw new IllegalArgumentException(
                        "DatetimeIndex 声明 freq=" + freq + " 但 instants 非升序:" + prev + " > " + cur);
                }
                if (cur != null) prev = cur;
            }
        }
        this.freq = freq;
        this.name = name;
    }

    /**
     * 工厂:已知频率升序时间点。
     * @param instants LocalDateTime[] 升序时间点(允许 null);长度 ≥ 0
     * @param freq     String 频率字符串,如 "1D";非 null
     * @return DatetimeIndex 新实例
     */
    public static DatetimeIndex of(LocalDateTime[] instants, String freq) {
        return new DatetimeIndex(instants, freq, null);
    }

    /**
     * 工厂:未知频率(频率后续可用 {@link #inferFreq()} 推断)。
     * 说明:允许乱序(不做升序检查)——
     * asof/resample 等下游会自动排序,但<b>依赖输入升序的语义由调用方保证</b>;
     * 需要升序校验的严格场景请走带频率的构造路径。
     * @param instants LocalDateTime[] 时间点;允许乱序(不做升序检查)
     * @return DatetimeIndex freq=null 的新实例
     */
    public static DatetimeIndex of(LocalDateTime[] instants) {
        // 不通过 freq 路径,直接构造避免升序校验
        DatetimeIndex di = new DatetimeIndex(instants, null, null);
        return di;
    }

    /**
     * @return int 长度(行数),≥ 0
     */
    public int size() { return instants.length; }

    /**
     * 取第 i 个时间点。
     * @param i int 下标 ∈ [0, size())
     * @return LocalDateTime 时间点;可能为 null(缺失)
     */
    public LocalDateTime get(int i) { return instants[i]; }

    /**
     * 时间点数组副本。
     * @return LocalDateTime[] 长度 == size();允许含 null
     */
    public LocalDateTime[] instants() { return instants.clone(); }

    /**
     * @return String 频率字符串,可能为 null(未知)
     */
    public String freq() { return freq; }

    /**
     * @return String 索引名,可能为 null
     */
    public String name() { return name; }

    /**
     * 切片 [start, end)。
     * @param start int 起始(含)
     * @param end   int 结束(不含)
     * @return DatetimeIndex 新实例,长度 = end-start,freq/name 继承
     */
    public DatetimeIndex slice(int start, int end) {
        DatetimeIndex di = new DatetimeIndex(
            Arrays.copyOfRange(instants, start, end), freq, name);
        return di;
    }

    /**
     * 找到第一个非 null 时间点所在下标(对齐 pandas first_valid_index)。
     * @return OptionalInt 第一个非 null 下标;全 null 时 empty
     */
    public OptionalInt firstValidIndex() {
        for (int i = 0; i < instants.length; i++) {
            if (instants[i] != null) return OptionalInt.of(i);
        }
        return OptionalInt.empty();
    }

    /**
     * 找到最后一个非 null 时间点所在下标(对齐 pandas last_valid_index)。
     * @return OptionalInt 最后一个非 null 下标;全 null 时 empty
     */
    public OptionalInt lastValidIndex() {
        for (int i = instants.length - 1; i >= 0; i--) {
            if (instants[i] != null) return OptionalInt.of(i);
        }
        return OptionalInt.empty();
    }

    /**
     * 时点筛选(对齐 pandas at_time):返回所有时间 == time 的下标。
     * @param time LocalTime 目标时刻,非 null
     * @return int[] 所有匹配下标(升序);无匹配返回空数组
     */
    public int[] atTime(LocalTime time) {
        Objects.requireNonNull(time, "time 不能为 null");
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < instants.length; i++) {
            if (instants[i] != null && instants[i].toLocalTime().equals(time)) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 时间段筛选(对齐 pandas between_time):返回 LocalTime ∈ [start, end] 的下标。
     * @param start LocalTime 起始时刻(含),非 null
     * @param end   LocalTime 结束时刻(含),非 null
     * @return int[] 所有匹配下标(升序);允许 start > end(过零点,如 22:00 - 02:00)
     */
    public int[] betweenTime(LocalTime start, LocalTime end) {
        Objects.requireNonNull(start, "start 不能为 null");
        Objects.requireNonNull(end, "end 不能为 null");
        List<Integer> out = new ArrayList<>();
        boolean crossMidnight = start.isAfter(end);
        for (int i = 0; i < instants.length; i++) {
            if (instants[i] == null) continue;
            LocalTime t = instants[i].toLocalTime();
            boolean inRange = crossMidnight
                ? (t.compareTo(start) >= 0 || t.compareTo(end) <= 0)
                : (t.compareTo(start) >= 0 && t.compareTo(end) <= 0);
            if (inRange) out.add(i);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 全扫描查找 ≤ label 的最后一个非 null 下标(对齐 pandas asof:返回最近的有效观测点)。
     * <p>因为"遇 &gt; label 即 break"的提前退出依赖升序,乱序输入会静默给错结果
     * (如 [3/1, 1/1] 查 2/1:第 0 个 3/1 &gt; 2/1 直接 break 返回 empty,而 1/1 明明 ≤ 2/1),
     * 所以全扫描(与 DataFrame.asof 语义一致,不依赖升序,取最后一个满足 instants[i] ≤ label 的行)。
     * @param label LocalDateTime 目标时间,非 null
     * @return OptionalInt 最大下标 i 满足 instants[i] != null 且 instants[i] <= label;
     *         无满足时 empty(所有时间都 > label 或全 null)
     */
    public OptionalInt asofIndex(LocalDateTime label) {
        Objects.requireNonNull(label, "label 不能为 null");
        int found = -1;
        for (int i = 0; i < instants.length; i++) {
            // 全扫描,不提前 break(乱序输入下"后面更小的时间点"仍是合法的 asof 观测)
            if (instants[i] != null && !instants[i].isAfter(label)) {
                found = i;
            }
        }
        return found >= 0 ? OptionalInt.of(found) : OptionalInt.empty();
    }

    /**
     * 推断频率(对齐 pandas infer_freq):扫前 3 个非 null 时间点的间隔。
     * 说明:仅 2 个有效点时无法验证一致性,
     * 直接按首间隔给频率(可能误判 —— 如 [1/1, 1/2] 与 [1/1, 2/1] 只凭 2 点无法区分)。
     * @return String 推断出的频率("1D"/"2H"/"unknown");无法确定时 "unknown"
     */
    public String inferFreq() {
        // 取前 3 个非 null 时间点
        List<LocalDateTime> valid = new ArrayList<>();
        for (LocalDateTime lt : instants) {
            if (lt != null) valid.add(lt);
            if (valid.size() >= 3) break;
        }
        if (valid.size() < 2) return "unknown";
        Duration d1 = Duration.between(valid.get(0), valid.get(1));
        if (valid.size() >= 3) {
            Duration d2 = Duration.between(valid.get(1), valid.get(2));
            if (!d1.equals(d2)) return "unknown";  // 非等间隔
        }
        long seconds = d1.getSeconds();
        if (seconds % (24 * 3600) == 0) return (seconds / (24 * 3600)) + "D";
        if (seconds % 3600 == 0) return (seconds / 3600) + "H";
        if (seconds % 60 == 0) return (seconds / 60) + "min";
        return seconds + "s";
    }

    /**
     * @return String 描述,最多前 10 个时间点
     */
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("DatetimeIndex[len=").append(size());
        if (freq != null) sb.append(", freq=").append(freq);
        sb.append("]\n");
        int cap = Math.min(size(), 10);
        for (int i = 0; i < cap; i++) sb.append(instants[i]).append('\n');
        if (size() > cap) sb.append("...\n");
        return sb.toString();
    }
}
