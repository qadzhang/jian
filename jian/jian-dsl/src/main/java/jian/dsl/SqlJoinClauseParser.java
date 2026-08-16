package jian.dsl;

import jian.core.DataFrame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ┌─ What : SqlJoinClauseParser —— JOIN ON 子句解析与执行(多等式 + 非等式附加条件)
// │  Why  : 因为只取第一对「别名.列 = 别名.列」的正则解析会把 ON 里的 AND 附加条件
// │         (ON l.k = r.k AND l.lv > 15)与 USING 多列(expandUsing 生成的 AND 等式链)
// │         静默丢弃 → 连接行数错误;且固定"先写者为左表列"会拒绝
// │         ON r.k2 = l.k1(右前左后)合法 SQL,所以独立解析完整 ON 子句
// │  Who  : SqlEngine.parseFrom 的每个 JOIN 段
// │  When : FROM ... JOIN ${x} ON <onText> 解析时
// │  Where: jian-dsl/SqlJoinClauseParser.java
// │  How  : 数据走向:onText → 按 AND 切段(字面量/括号感知)→ 等式段归并为 merge key 对
// │         → left.merge(right, how, leftKeys, rightKeys) → 非等式段按合并后列名改写
// │         → PrattEngine.query 逐条过滤 → 结果 DataFrame。
// │         关键变量变化:
// │           - leftKeys/rightKeys:每识别一对等式各 push 一个(多对多列 merge);
// │           - aliasIsLeft:等式段解析出的「别名 → 侧向」映射(true=左/false=右),
//             供非等式段区分重名列后缀(_x/_y);
// │           - filters:非等式段(如 l.lv > 15)改写别名引用后累积;
// │           - current:merge 结果,再被逐条 filter 收窄。
// │         逻辑路线:
// │           路径 A(等式段):列在哪侧表存在即归哪侧 —— 先按书写顺序试,列不存在再换向
// │             (支持右前左后);两种方向都解析不出 → IAE;
// │           路径 B(非等式段):「别名.列」→ 合并后实际列名(优先无后缀;重名按别名侧向
// │             选 _x/_y)→ Pratt 过滤;所有候选都不存在 → IAE;
// │           路径 C(一个等式都没有):IAE(笛卡尔积走 CROSS JOIN 预处理路径,不进这里)。
/**
 * JOIN ON 子句解析器(完整解析多等式与非等式附加条件)。
 *
 * <p>支持形态:
 * <ul>
 *   <li>{@code ON l.k = r.k}(单等式);</li>
 *   <li>{@code ON l.k1 = r.k2 AND l.a = r.b}(多等式:全部作为 merge key,对齐 SQL 复合键);</li>
 *   <li>{@code ON l.k = r.k AND l.lv > 15}(非等式附加条件:merge 后按列过滤);</li>
 *   <li>{@code ON r.k = l.k}(右表列在前:按「列存在于哪侧表」自动定向,两侧同列名时
 *       按书写顺序默认先写者为左)。</li>
 * </ul>
 *
 * <p>USING(col1, col2) 经 SqlPreprocessor.expandUsing 展开为
 * {@code ON a.col1 = b.col1 AND a.col2 = b.col2} 后走同一多等式路径,多列复合键语义一致。
 */
final class SqlJoinClauseParser {

    private SqlJoinClauseParser() {}

    /** 「别名.列」引用(UCC:别名/列名支持中文)。 */
    private static final Pattern REF_PATTERN =
            Pattern.compile("(\\w+)\\.(\\w+)", Pattern.UNICODE_CHARACTER_CLASS);

    /** 完整等式段:别名.列 = 别名.列(整段 matches,不允许混入其它运算符)。 */
    private static final Pattern EQUI_PATTERN =
            Pattern.compile("\\s*(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)\\s*",
                    Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 解析 ON 子句并执行 merge + 附加条件过滤。
     *
     * @param left DataFrame 左表(链式 join 时为已累积的中间结果),非 null
     * @param right DataFrame 右表,非 null
     * @param how String join 类型(inner/left/right/outer,大小写不敏感),非 null
     * @param onText String ON 子句文本(不含 "ON" 关键字),非 null
     * @return DataFrame merge 并应用全部非等式条件后的结果
     * @throws IllegalArgumentException 等式两侧列均不存在 / 无任何等式 / 附加条件列不存在
     */
    static DataFrame join(DataFrame left, DataFrame right, String how, String onText) {
        // 伪代码:
        //   1. onText 按顶层 AND 切段(跳过字符串字面量与括号区)
        //   2. 每段:整段匹配「别名.列 = 别名.列」→ 解析侧向、push 进 leftKeys/rightKeys;
        //      否则视为附加条件,改写「别名.列」为合并后列名后收进 filters
        //   3. leftKeys 为空 → IAE(无等式的笛卡尔积请用 CROSS JOIN)
        //   4. left.merge(right, how, leftKeys, rightKeys)
        //   5. 逐条 filter(PrattEngine.query)收窄,返回
        List<String> leftKeys = new ArrayList<>();
        List<String> rightKeys = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        Map<String, Boolean> aliasIsLeft = new HashMap<>();
        for (String seg : splitByAnd(onText)) {
            String t = seg.trim();
            if (t.isEmpty()) continue;
            Matcher eq = EQUI_PATTERN.matcher(t);
            if (eq.matches()) {
                String aliasA = eq.group(1), colA = eq.group(2);
                String aliasB = eq.group(3), colB = eq.group(4);
                if (left.columnIndex(colA) >= 0 && right.columnIndex(colB) >= 0) {
                    leftKeys.add(colA);
                    rightKeys.add(colB);
                    aliasIsLeft.put(aliasA, true);
                    aliasIsLeft.put(aliasB, false);
                } else if (left.columnIndex(colB) >= 0 && right.columnIndex(colA) >= 0) {
                    // 右前左后:按「列存在于哪侧表」换向(不固定先写者为左,右表列在前的合法 SQL 也接受)
                    leftKeys.add(colB);
                    rightKeys.add(colA);
                    aliasIsLeft.put(aliasB, true);
                    aliasIsLeft.put(aliasA, false);
                } else {
                    throw new IllegalArgumentException("JOIN ON 等式列不存在:" + t
                            + ";左表列:" + left.columnNames() + ",右表列:" + right.columnNames());
                }
            } else {
                filters.add(t);
            }
        }
        if (leftKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "JOIN ON 至少需要一个「别名.列 = 别名.列」等式条件(笛卡尔积请用 CROSS JOIN):" + onText);
        }
        DataFrame merged = left.merge(right, how.toLowerCase(),
                leftKeys.toArray(new String[0]), rightKeys.toArray(new String[0]), null);
        for (String f : filters) {
            String rewritten = rewriteRefs(f, left, right, merged, aliasIsLeft);
            merged = PrattEngine.query(merged, SqlPreprocessor.normalizeSqlExpr(rewritten), Params.EMPTY);
        }
        return merged;
    }

    // ┌─ What : rewriteRefs —— 把附加条件里的「别名.列」改写为合并结果里的实际列名
    // │  Why  : merge 后重名列带 _x/_y 后缀、join key 合并为无后缀单列,附加条件
    // │         (如 l.lv > 15)里的原引用必须映射到合并后真实存在的列
    // │  Who  : join(路径 B:非等式段预处理)
    // │  When : 每次 filter 前
    // │  Where: jian-dsl/SqlJoinClauseParser.java
    // │  How  : 数据走向:cond 文本 → 逐个「别名.列」match → resolveMergedColumn 换名 → 拼回。
    // │         关键变量变化:last 游标跟踪已复制前缀;out 累积改写后的整段条件。
    // │         逻辑路线:全部 match 换名拼接;任一列解析不出 → IAE(带条件原文)。
    private static String rewriteRefs(String cond, DataFrame left, DataFrame right,
                                      DataFrame merged, Map<String, Boolean> aliasIsLeft) {
        Matcher m = REF_PATTERN.matcher(cond);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(cond, last, m.start());
            out.append(resolveMergedColumn(m.group(2), m.group(1), left, right, merged, cond, aliasIsLeft));
            last = m.end();
        }
        out.append(cond.substring(last));
        return out.toString();
    }

    /**
     * 解析「别名.列」在合并结果中的实际列名。
     * 候选顺序:原名(同名 join key / 无重名列)→ 按别名侧向的重名后缀(左 _x / 右 _y)
     * → 另一侧后缀兜底;全部不存在 → IAE。
     */
    private static String resolveMergedColumn(String col, String alias, DataFrame left, DataFrame right,
                                              DataFrame merged, String cond, Map<String, Boolean> aliasIsLeft) {
        // 侧向优先级:等式段解析出的别名侧向 > 列单侧存在性 > 默认先左后右
        Boolean side = aliasIsLeftOf(alias, col, left, right, aliasIsLeft);
        List<String> candidates = new ArrayList<>();
        candidates.add(col);
        if (side == null || side) candidates.add(col + "_x");
        if (side == null || !side) candidates.add(col + "_y");
        for (String c : candidates) {
            if (merged.columnIndex(c) >= 0) return c;
        }
        throw new IllegalArgumentException("JOIN ON 附加条件列不存在:「" + alias + "." + col
                + "」(条件:" + cond + ");合并结果列:" + merged.columnNames());
    }

    /** 别名侧向判定:true=左 / false=右 / null=未知(按等式段映射,退化到列存在性)。 */
    private static Boolean aliasIsLeftOf(String alias, String col, DataFrame left, DataFrame right,
                                         Map<String, Boolean> aliasIsLeft) {
        Boolean known = aliasIsLeft.get(alias);
        if (known != null) return known;
        boolean fromLeft = left.columnIndex(col) >= 0;
        boolean fromRight = right.columnIndex(col) >= 0;
        if (fromLeft && !fromRight) return true;
        if (fromRight && !fromLeft) return false;
        return null;
    }

    // ┌─ What : splitByAnd —— 按顶层 AND 切分 ON 子句
    // │  Why  : 多等式/附加条件由 AND 连接;字面量里的 "and" 与括号内的 AND 不能切
    // │  Who  : join(段切分)
    // │  When : 每次 ON 解析
    // │  Where: jian-dsl/SqlJoinClauseParser.java
    // │  How  : 逐字符扫描;引号区(含 '' 翻倍)整体跳过;括号深度>0 跳过;
    // │         深度 0 的词边界 "and" 处切段。变量:depth 括号深度、start 当前段起点。
    private static List<String> splitByAnd(String onText) {
        List<String> parts = new ArrayList<>();
        String lower = onText.toLowerCase();
        int n = lower.length();
        int depth = 0, start = 0;
        for (int i = 0; i < n; i++) {
            char c = lower.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < n) {
                    if (lower.charAt(i) == q) {
                        if (i + 1 < n && lower.charAt(i + 1) == q) { i += 2; continue; }  // '' 翻倍
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && lower.startsWith("and", i)
                    && (i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1)))
                    && (i + 3 == n || !Character.isLetterOrDigit(lower.charAt(i + 3)))) {
                parts.add(onText.substring(start, i));
                i += 2;            // 加上 for 的 i++ 共跳过 3 字符 "and"
                start = i + 1;
            }
        }
        parts.add(onText.substring(start));
        return parts;
    }
}
