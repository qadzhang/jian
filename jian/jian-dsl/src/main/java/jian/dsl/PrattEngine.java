package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ┌─ What : PrattEngine —— L1/L2 表达式引擎(对齐规范 07 §3.1 手写 Pratt parser)
// │  Why  : 规范 07;零运行时依赖(纯 JDK),支持 query/eval/三元/参数/${var}/多语句
// │  Who  : 由 Dsl.query/eval 委托
// │  When : df.query / df.eval
// │  Where: jian-dsl/PrattEngine.java
// │  How  : 三阶段:
// │           阶段1 Lexer:展开 ${var} → 字符串 → Token(数字/字符串/标识符/运算符/关键字);
// │           阶段2 Parser(Pratt 优先级):|| < && < ! < 比较/between/like/in < +- < */% < 一元 < 三元 < primary;
// │           阶段3 Evaluator:逐行求值,列名从 binding 取值。
/**
 * L1/L2 Pratt parser 引擎(规范 07 §3.1)。
 *
 * <p>支持的语法子集:
 * <ul>
 *   <li>比较 {@code > < >= <= == !=};逻辑 {@code && || !}{@code (and/or/not)};</li>
 *   <li>算术 {@code + - * / %};三元 {@code cond ? a : b}(嵌套支持);</li>
 *   <li>谓词 {@code between X and Y} / {@code like 'pat%'} / {@code in (...)} / {@code is [not] null};</li>
 *   <li>字面量:数字 / 字符串(单双引号)/ true/false/null;</li>
 *   <li>参数:${@code ${name}} 占位由 {@link Params} 展开;</li>
 *   <li>多语句(L2 eval):分号分隔的 `col = expr` 列表,派生多列。</li>
 * </ul>
 */
final class PrattEngine {

    private PrattEngine() {}

    /**
     * L1 query:对每行求值 expr,返回满足的行组成新 DataFrame。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 布尔表达式(已展开 ${name} 占位),非 null
     * @param params Params 命名参数绑定,非 null(无参传 Params.EMPTY)
     * @return DataFrame 满足 expr 的行组成的新 DataFrame
     */
    static DataFrame query(DataFrame df, String expr, Params params) {
        String expanded = expandParams(expr, params);
        Node ast = parse(expanded);
        boolean[] mask = new boolean[df.rowCount()];
        List<String> cols = df.columnNames();
        for (int r = 0; r < df.rowCount(); r++) {
            Object v = ast.eval(new RowBinding(df, cols, r));
            mask[r] = toBool(v);
        }
        return df.filter(mask);
    }

    /**
     * L2 eval:派生新列(支持 `name = expr; name2 = expr2` 多语句)。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 赋值表达式(已展开占位),非 null;分号或换行分隔多语句
     * @param params Params 命名参数绑定,非 null(无参传 Params.EMPTY)
     * @return DataFrame 加了新列后的 DataFrame
     */
    static DataFrame eval(DataFrame df, String expr, Params params) {
        String expanded = expandParams(expr, params);
        // 多语句:按分号或换行切分(末尾可省分号)
        String[] stmts = expanded.split("[;\\n]+");
        DataFrame cur = df;
        for (String stmt : stmts) {
            String s = stmt.trim();
            if (s.isEmpty()) continue;
            cur = evalSingle(cur, s);
        }
        return cur;
    }

    /** 单条 `name = expr` 求值,返回加新列的 DataFrame。 */
    private static DataFrame evalSingle(DataFrame df, String stmt) {
        // 找最外层 "="(不在字符串内,简化:找第一个 = 且不是 == >= <= !=)
        int eqIdx = findTopLevelAssign(stmt);
        if (eqIdx < 0) {
            throw new IllegalArgumentException("eval 表达式需为 \"name = expr\" 形式,实际:" + stmt);
        }
        String name = stmt.substring(0, eqIdx).trim();
        String exprStr = stmt.substring(eqIdx + 1).trim();
        Node ast = parse(exprStr);
        List<String> cols = df.columnNames();
        Object[] values = new Object[df.rowCount()];
        for (int r = 0; r < df.rowCount(); r++) {
            values[r] = ast.eval(new RowBinding(df, cols, r));
        }
        // 推断新列类型
        Object[][] wrapped = new Object[df.rowCount()][1];
        for (int r = 0; r < df.rowCount(); r++) wrapped[r][0] = values[r];
        Schema sub = Schema.infer(List.of(name), wrapped);
        DType dt = sub.dtypeAt(0);
        // 构造新列 + 加到 DataFrame
        return df.assign(name, r -> values[r]).astype(name, dt);
    }

    /** 找顶层 "="(跳过 == >= <= != 和字符串内)。简化实现。 */
    private static int findTopLevelAssign(String s) {
        boolean inStr = false; char strCh = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == strCh) inStr = false;
                continue;
            }
            if (c == '\'' || c == '"') { inStr = true; strCh = c; continue; }
            if (c == '=') {
                // 跳过 == >= <= !=
                char prev = i > 0 ? s.charAt(i - 1) : 0;
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                if (next == '=') { i++; continue; }  // ==
                if (prev == '>' || prev == '<' || prev == '!' || prev == '=') continue;
                return i;
            }
        }
        return -1;
    }

    /**
     * 展开 ${name} 占位 → 字面量(JSON-ish 形式:字符串加引号,数字直接,空保留 null)。
     *
     * @param expr String 原始表达式,非 null;可含 ${name} 占位
     * @param params Params 命名参数绑定;null 或 Params.EMPTY 时原样返回 expr
     * @return String 占位已展开为字面量的表达式
     */
    static String expandParams(String expr, Params params) {
        if (params == null || params == Params.EMPTY) return expr;
        Matcher m = Pattern.compile("\\$\\{(\\w+)\\}", Pattern.UNICODE_CHARACTER_CLASS).matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = params.get(key);
            String rep;
            if (val == null) rep = "null";
            else if (val instanceof Number) rep = val.toString();
            else if (val instanceof Boolean) rep = val.toString();
            // 值里的字面反斜杠先翻倍(解析端 string() 已支持反斜杠转义,编码端须同步,
            // 否则参数值含 "\n" 两字符会被解码为换行)
            else rep = "'" + val.toString().replace("\\", "\\\\").replace("'", "''") + "'";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        // 因为"非零即 true"的隐式数值转布尔会掩盖逻辑 bug(pandas/numexpr 对数值做
        // 逻辑运算符直接语法错),所以数值不做隐式布尔转换、直接 fail-fast 抛 IAE,与 SimpleQueryParser 同步。
        if (v instanceof Number) {
            throw new IllegalArgumentException("逻辑运算符要求布尔操作数,实际 "
                    + v.getClass().getSimpleName() + " (" + v + ");如需判空请用 is null/非零请显式 == 0 判断(对齐 pandas)");
        }
        throw new IllegalArgumentException("表达式最终结果非布尔:" + v);
    }

    // ======================== AST ========================

    sealed interface Node permits NumLit, StrLit, BoolLit, NullLit, ColRef, BinOp, Logic, Not, Ternary, Between, Like, In, IsNull, IsBool, FuncCall {
        Object eval(Binding b);
    }

    record NumLit(Number v) implements Node {
        // 因为整数字面量若一律按 double 携带,9223372036854775806 会被舍入为 2^63,
        // 与 LONG 列比较时误匹配 Long.MAX 行(pandas numexpr 按整数精确),
        // 所以整数字面量按 long 解析携带(Long)。与 SimpleQueryParser.NumLit 双引擎同步。
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果(整数装 Long,浮点装 Double) */
        public Object eval(Binding b) { return v; }
    }
    record StrLit(String v) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) { return v; }
    }
    record BoolLit(boolean v) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) { return v; }
    }
    record NullLit() implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) { return null; }
    }
    record ColRef(String name) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) { return b.get(name); }
    }
    record BinOp(String op, Node l, Node r) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        @SuppressWarnings("unchecked")
        public Object eval(Binding b) {
            Object a = l.eval(b), c = r.eval(b);
            if (op.equals("+") && (a instanceof String || c instanceof String)) {
                // 缺失传播:任一操作数缺失(null/NaN)时结果为缺失(null),
                // 不拼出字面量 "nullx"(对齐 pandas str+NaN→NaN)
                if (a == null || c == null
                        || (a instanceof Double da && da.isNaN()) || (c instanceof Double dc && dc.isNaN())) {
                    return null;
                }
                return String.valueOf(a) + String.valueOf(c);
            }
            // null(缺失行)处理:
            // 混型改抛后,可空列的缺失行(get→null)落到下方混型分支会抛 IAE,导致含 null 的
            // WHERE 整查询崩溃(如 df.query("nullableLong > 5"))。对齐 pandas NaN 传播 + DataFrame.cmp:
            //   null 参与比较 → == false、!= true、顺序比较 false(缺失行不满足,被 WHERE 排除);
            //   null 参与算术 → null(缺失传播)。详见 doc/00-overview.md §3.5 + §10.16。
            if (a == null || c == null) {
                return switch (op) {
                    case "==" -> false; case "!=" -> true;
                    case "+", "-", "*", "/", "%" -> null;
                    default -> false;   // > < >= <= 含 null → false
                };
            }
            if (a instanceof Number && c instanceof Number) {
                Number na = (Number) a, nb = (Number) c;
                // 对齐 DataFrame.cmp:双整数的"比较"运算走 long 精确路径(避免 Long.MAX_VALUE
                // 等大值经 double 丢精度,致 df.query("bigid > X") 与 df.colGt 结果不一致);
                // 算术仍走 double(pandas query 算术默认浮点)。
                if (jian.core.DataFrameCompare.isIntegralNumber(na) && jian.core.DataFrameCompare.isIntegralNumber(nb)
                        && (op.equals(">") || op.equals("<") || op.equals(">=") || op.equals("<=")
                            || op.equals("==") || op.equals("!="))) {
                    long xl = na.longValue(), yl = nb.longValue();
                    return switch (op) {
                        case ">" -> xl > yl; case "<" -> xl < yl; case ">=" -> xl >= yl;
                        case "<=" -> xl <= yl; case "==" -> xl == yl; case "!=" -> xl != yl;
                        default -> throw new IllegalStateException(op);
                    };
                }
                double x = na.doubleValue(), y = nb.doubleValue();
                return switch (op) {
                    case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y; case "%" -> x % y;
                    case ">" -> x > y; case "<" -> x < y; case ">=" -> x >= y; case "<=" -> x <= y;
                    case "==" -> x == y; case "!=" -> x != y;
                    default -> throw new IllegalStateException(op);
                };
            }
            // 同型且 Comparable → compareTo(覆盖 String==String、LocalDateTime、BigDecimal 等;c 必同型,不 CCE)
            if (a != null && c != null && a.getClass() == c.getClass() && a instanceof Comparable ca) {
                int cmp = ((Comparable<Object>) ca).compareTo(c);
                return switch (op) {
                    case "==" -> cmp == 0; case "!=" -> cmp != 0;
                    case ">" -> cmp > 0; case "<" -> cmp < 0; case ">=" -> cmp >= 0; case "<=" -> cmp <= 0;
                    default -> throw new IllegalStateException("运算符 " + op);
                };
            }
            // 混型 / 不可比 / 含 null(对齐 pandas + 与 DataFrame.cmp 统一):
            //   ① 混型 == 恒 false、!= 恒 true(pandas 元素级相等不抛;null 亦不相等,对齐 NaN 语义)
            //   ② 混型 > < >= <= 抛 IllegalArgumentException(pandas 抛 TypeError/NotImplementedError)
            //   三入口(SimpleQueryParser、DataFrame.cmp、PrattEngine)统一对齐 pandas,
            //   不允许 String 字典序这类未声明偏离。详见 doc/00-overview.md §10.16。
            return switch (op) {
                case "==" -> false;
                case "!=" -> true;
                case ">", "<", ">=", "<=" -> throw new IllegalArgumentException(
                    "不支持 " + (a == null ? "null" : a.getClass().getSimpleName()) + " 与 "
                    + (c == null ? "null" : c.getClass().getSimpleName()) + " 的 '" + op + "' 比较(混型);"
                    + "请先统一类型(对齐 pandas TypeError)");
                default -> throw new IllegalStateException("运算符 " + op + " 不支持 " + (a == null ? "null" : a.getClass()));
            };
        }
    }
    record Logic(String op, Node l, Node r) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) {
            if (op.equals("&&")) return toBool(l.eval(b)) && toBool(r.eval(b));
            return toBool(l.eval(b)) || toBool(r.eval(b));
        }
    }
    record Not(Node e) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) { return !toBool(e.eval(b)); }
    }
    record Ternary(Node c, Node a, Node b) implements Node {
    /**
     * @param b Binding 变量绑定上下文,非 null;@return Object 求值结果
     * @param bnd 参数;非 null
     */
        public Object eval(Binding bnd) {
            return toBool(c.eval(bnd)) ? a.eval(bnd) : b.eval(bnd);
        }
    }
    record Between(Node e, Node lo, Node hi) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) {
            Object v = e.eval(b);
            if (v == null) return false;
            Object loV = lo.eval(b), hiV = hi.eval(b);
            if (loV == null || hiV == null) return false;
            // 因为非 Number 边界/值直接强转会抛裸 CCE,所以先判型并抛 IAE 带类型提示
            if (!(v instanceof Number) || !(loV instanceof Number) || !(hiV instanceof Number)) {
                throw new IllegalArgumentException("BETWEEN 要求数值:值=" + v + "( " + v.getClass().getSimpleName()
                    + "),边界=" + loV + "/" + hiV);
            }
            double x = ((Number) v).doubleValue();
            return x >= ((Number) loV).doubleValue() && x <= ((Number) hiV).doubleValue();
        }
    }
    record Like(Node e, String pattern) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) {
            Object v = e.eval(b);
            if (v == null) return false;
            // 安全:只把 % _ 当通配,其余正则元字符一律转义(防正则注入)
            return v.toString().matches(jian.core.LikePattern.toRegex(pattern));
        }
    }

    /** 函数调用(空值函数 nvl/coalesce/ifnull,规范 07 §2.4)。 */
    record FuncCall(String name, List<Node> args) implements Node {
        /**
         * @param b Binding 变量绑定上下文,非 null
         * @return Object 求值结果
         *         (NaN 也视为缺失,与 AGENTS.md §3.5 一致)
         */
        public Object eval(Binding b) {
            String fn = name.toLowerCase();
            if (fn.equals("nvl") || fn.equals("coalesce") || fn.equals("ifnull")) {
                // 空值函数:返回第一个非缺失参数(全缺失返回 null)。
                // 缺失 = null 或 DOUBLE 列的 NaN(只判 null 会漏判 DOUBLE 列 NaN)。
                for (Node a : args) {
                    Object v = a.eval(b);
                    if (!isMissing(v)) return v;
                }
                return null;
            }
            throw new IllegalArgumentException("未知函数 '" + name + "',支持:nvl / coalesce / ifnull");
        }
    }

    record In(Node e, List<Node> items) implements Node {
        /** @param b Binding 变量绑定上下文,非 null;@return Object 求值结果 */
        public Object eval(Binding b) {
            Object v = e.eval(b);
            for (Node n : items) {
                Object item = n.eval(b);
                if (v == null) { if (item == null) return true; }
                else if (jian.core.DataFrameCompare.valueEquals(v, item)) return true;
            }
            return false;
        }
    }

    record IsNull(Node e, boolean negate) implements Node {
        /**
         * @param b Binding 变量绑定上下文,非 null
         * @return Boolean true=值缺失(v == null,或 v 是 DOUBLE 列的 NaN)
         *         (DSL 引擎需识别 DOUBLE 列的 NaN 为缺失,
         *          与 AGENTS.md §3.5 缺失值语义一致;否则 v is null 在 NaN 上永远返回 false)
         */
        public Object eval(Binding b) {
            Object v = e.eval(b);
            boolean missing = isMissing(v);
            return negate ? !missing : missing;
        }
    }

    /**
     * is [not] true / is [not] false 谓词(SQL 风格;pandas 无此语法属超集增强)。
     * <p>语义对齐 SQL 三值逻辑:
     * <ul>
     *   <li>{@code flag is true}:仅当值为 Boolean.TRUE 才 true;null/数值/其它类型均 false</li>
     *   <li>{@code flag is not true}:对 false 与 null 均 true(三值逻辑取反)</li>
     * </ul>
     */
    record IsBool(Node e, boolean wantTrue, boolean negate) implements Node {
        /**
         * @param b Binding 变量绑定上下文,非 null
         * @return Boolean 值是否等于目标布尔(null 不等于任何目标,由 negate 决定最终取反)
         */
        public Object eval(Binding b) {
            Object v = e.eval(b);
            boolean eq = Boolean.valueOf(wantTrue).equals(v);
            return negate ? !eq : eq;
        }
    }

    /**
     * 判定一个 DSL 求值结果是否"缺失":null 或 DOUBLE 列的 NaN。
     * <p>DOUBLE 列内部用 NaN 表示缺失(AGENTS.md §3.5),
     * DSL 引擎的 is null / nvl / coalesce / ifnull 必须把 NaN 当缺失处理。
     */
    private static boolean isMissing(Object v) {
        if (v == null) return true;
        if (v instanceof Double d && d.isNaN()) return true;
        return false;
    }

    // ======================== Lexer ========================

    enum TT { NUM, STR, IDENT, OP, LP, RP, COMMA, QMARK, COLON, END }
    record Token(TT t, String s, int pos) {}

    static final class Lexer {
        private final String src; private int i = 0;
        Lexer(String s) { this.src = s; }
        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '(') { out.add(new Token(TT.LP, "(", i++)); continue; }
                if (c == ')') { out.add(new Token(TT.RP, ")", i++)); continue; }
                if (c == ',') { out.add(new Token(TT.COMMA, ",", i++)); continue; }
                if (c == '?') { out.add(new Token(TT.QMARK, "?", i++)); continue; }
                if (c == ':') { out.add(new Token(TT.COLON, ":", i++)); continue; }
                if (c == '\'' || c == '"') { out.add(string()); continue; }
                // 反引号标识符(`col with space`,pandas query 同款)。
                // 内容整体作 IDENT,不再做词法分析 → 列名可含空格/点/减号/中文等(反引号本身除外)。
                if (c == '`') {
                    int start = i; i++;
                    StringBuilder sb = new StringBuilder();
                    while (i < src.length() && src.charAt(i) != '`') sb.append(src.charAt(i++));
                    if (i >= src.length()) throw new IllegalArgumentException("反引号标识符未闭合,起始于 " + start);
                    i++;
                    out.add(new Token(TT.IDENT, sb.toString(), start)); continue;
                }
                if (Character.isDigit(c) || (c == '-' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))
                        && (out.isEmpty() || out.get(out.size() - 1).t == TT.OP || out.get(out.size() - 1).t == TT.LP
                            || out.get(out.size() - 1).t == TT.COMMA))) {
                    out.add(number()); continue;
                }
                // 增补平面字符(U+10000+,如 CJK 扩展B「𠀀」、emoji)也进标识符。
                // 因为按单个 char 判断 isLetter 时孤立高代理(U+D800~U+DBFF)不算字母,
                // 词法器会直接抛「无法识别的字符」,列名含「𠀀」的 WHERE/eval 无法解析
                if (Character.isLetter(c) || c == '_' || Character.isHighSurrogate(c)) { out.add(ident()); continue; }
                String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                if (two.equals("&&") || two.equals("||") || two.equals(">=") || two.equals("<=")
                        || two.equals("==") || two.equals("!=")) {
                    out.add(new Token(TT.OP, two, i)); i += 2; continue;
                }
                if (c == '>' || c == '<' || c == '!' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%') {
                    out.add(new Token(TT.OP, String.valueOf(c), i)); i++; continue;
                }
                throw new IllegalArgumentException("无法识别的字符 '" + c + "' 在位置 " + i);
            }
            out.add(new Token(TT.END, "", i));
            return out;
        }
        /**
         * 词法:读取单/双引号字符串字面量。
         * 转义语义:ANSI SQL 标准的"单引号翻倍"('' → 字面量 '),与写入端 toLiteral/expandParams
         * 一致(不"反斜杠吞字符")。在 '' 翻倍的基础上补齐反斜杠常见转义
         * (\\ \' \" \n \t),与 SimpleQueryParser 口径一致;\\% \\_ 及其余 \\x 保留双字符
         * (like 转义由 LikePattern.toRegex 解析,其余字面保留,不吞字符)。
         */
        private Token string() {
            char q = src.charAt(i); int start = i; i++;
            StringBuilder sb = new StringBuilder();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == q) {
                    // '' 翻倍 = 转义的单引号(SQL 标准);双引号字符串同理
                    if (i + 1 < src.length() && src.charAt(i + 1) == q) {
                        sb.append(q); i += 2;
                        continue;
                    }
                    i++;
                    return new Token(TT.STR, sb.toString(), start);
                }
                if (c == '\\' && i + 1 < src.length()) {
                    char n = src.charAt(i + 1);
                    switch (n) {
                        case '\\' -> { sb.append('\\'); i += 2; continue; }
                        case '\'', '"' -> { sb.append(n); i += 2; continue; }
                        case 'n' -> { sb.append('\n'); i += 2; continue; }
                        case 't' -> { sb.append('\t'); i += 2; continue; }
                        // \% \_ 及其它:保留 "\x" 双字符(like 转义由 LikePattern 处理,其余字面保留)
                        default -> { sb.append(c).append(n); i += 2; continue; }
                    }
                }
                sb.append(c); i++;
            }
            throw new IllegalArgumentException("字符串未闭合,起始于 " + start);
        }
        private Token number() {
            // 支持科学计数法字面量:
            //   [0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)? —— 1e5 / 1.5e-3(pandas query 支持;
            //   只收数字与小数点的话,1e5 会被拆成 NUM(1)+IDENT(e5) 报
            //   "尾部多余 token 'e5'")。e/E 后必须跟数字(或 +- 后数字),否则回退
            //   (如 "1e" 不当科学计数法,按旧口径拆 NUM(1)+IDENT(e) 由解析层报错)。
            // 关键变量变化:i 从数字起点推进;save 记住 e 的位置供指数不完整时回退。
            int start = i;
            if (src.charAt(i) == '-') i++;
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
            if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
                if (i < src.length() && Character.isDigit(src.charAt(i))) {
                    while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
                } else {
                    i = save;   // 指数不完整:回退,e 留给后续 token
                }
            }
            return new Token(TT.NUM, src.substring(start, i), start);
        }
        private Token ident() {
            // codePoint 感知 —— cp>0xFFFF(增补平面)无条件当标识符字符,
            // 字母/数字按 codePoint 判定(单 char 判定会把代理对拆散误拒)
            int start = i;
            while (i < src.length()) {
                int cp = src.codePointAt(i);
                if (cp > 0xFFFF || Character.isLetterOrDigit(cp) || cp == '_') i += Character.charCount(cp);
                else break;
            }
            return new Token(TT.IDENT, src.substring(start, i), start);
        }
    }

    // ======================== Parser(Pratt)========================

    static final class Parser {
        private final List<Token> toks; private int pos = 0;
        Parser(List<Token> t) { toks = t; }

        Node parse() {
            Node n = parseOr();
            if (peek().t != TT.END) {
                throw new IllegalArgumentException("尾部多余 token '" + peek().s + "' 在 " + peek().pos);
            }
            return n;
        }

        // || (最低)
        private Node parseOr() {
            Node left = parseAnd();
            while (isOp("||") || isKw("or")) { consume(); left = new Logic("||", left, parseAnd()); }
            return left;
        }
        // &&
        private Node parseAnd() {
            Node left = parseNot();
            while (isOp("&&") || isKw("and")) { consume(); left = new Logic("&&", left, parseNot()); }
            return left;
        }
        // !
        private Node parseNot() {
            if (isOp("!") || isKw("not")) { consume(); return new Not(parseNot()); }
            return parseTernary();
        }
        // ? :
        private Node parseTernary() {
            Node cond = parseComparison();
            if (peek().t == TT.QMARK) {
                consume();
                Node a = parseTernary();
                if (peek().t != TT.COLON) throw new IllegalArgumentException("三元缺少 ':' 在 " + peek().pos);
                consume();
                Node b = parseTernary();
                return new Ternary(cond, a, b);
            }
            return cond;
        }
        // 比较 / between / like / in / is
        private Node parseComparison() {
            Node left = parseAddSub();
            // 中缀 not/notin 谓词取反。因为只支持前缀 NOT 时,"a not in (2,4)" 解析完 a 后
            // not 成"尾部多余 token"直接报错,所以支持中缀形式:
            // not in / notin / not like / not between / not is(= is not)。
            if (isKw("notin")) { consume(); return new Not(parseInTail(left)); }
            if (isKw("not")) {
                int save = pos;
                consume();
                if (isKw("in") || isKw("like") || isKw("between") || isKw("is")) {
                    return new Not(parsePredicate(left));
                }
                pos = save;   // 非谓词前缀(语法错场景,交由上层报"尾部多余 token")
            }
            if (isKw("between") || isKw("like") || isKw("in") || isKw("is")) {
                return parsePredicate(left);
            }
            if (peek().t == TT.OP && jian.core.DataFrameCompare.isCmpOp(peek().s)) {
                String op = consume().s;
                return new BinOp(op, left, parseAddSub());
            }
            return left;
        }
        /**
         * 谓词尾部解析(between/like/in/is,不含 not 前缀,由调用方包 Not)。
         * 数据走向:left(已解析的左操作数)→ 按谓词关键字分支 → 谓词节点。
         */
        private Node parsePredicate(Node left) {
            if (isKw("between")) {
                consume();
                Node lo = parseAddSub();
                expectKw("and");
                Node hi = parseAddSub();
                return new Between(left, lo, hi);
            }
            if (isKw("like")) {
                consume();
                if (peek().t != TT.STR) throw new IllegalArgumentException("like 需字符串 在 " + peek().pos);
                return new Like(left, consume().s);
            }
            if (isKw("in")) { consume(); return parseInTail(left); }
            // is [not] null / is [not] true|false
            consume();  // is
            boolean neg = false;
            if (isKw("not")) { neg = true; consume(); }
            // is [not] true / is [not] false(SQL 风格,pandas 无此语法属超集)。
            if (isKw("true")) { consume(); return new IsBool(left, true, neg); }
            if (isKw("false")) { consume(); return new IsBool(left, false, neg); }
            expectKw("null");
            return new IsNull(left, neg);
        }
        /** in 谓词尾部:已吃掉 in 关键字,解析 ( item, ... ) 列表(元素行级求值,支持列引用)。 */
        private Node parseInTail(Node left) {
            if (peek().t != TT.LP) throw new IllegalArgumentException("in 需 ( 在 " + peek().pos);
            consume();
            List<Node> items = new ArrayList<>();
            if (peek().t != TT.RP) {
                items.add(parseOr());
                while (peek().t == TT.COMMA) { consume(); items.add(parseOr()); }
            }
            if (peek().t != TT.RP) throw new IllegalArgumentException("in 缺 ) 在 " + peek().pos);
            consume();
            return new In(left, items);
        }
        // + -
        private Node parseAddSub() {
            Node left = parseMulDiv();
            while (isOp("+") || isOp("-")) { String op = consume().s; left = new BinOp(op, left, parseMulDiv()); }
            return left;
        }
        // * / %
        private Node parseMulDiv() {
            Node left = parseUnary();
            while (isOp("*") || isOp("/") || isOp("%")) { String op = consume().s; left = new BinOp(op, left, parseUnary()); }
            return left;
        }
        // 一元 -
        private Node parseUnary() {
            if (isOp("-")) { consume(); return new BinOp("*", new NumLit(-1L), parseUnary()); }
            return parsePrimary();
        }
        // primary
        private Node parsePrimary() {
            Token t = peek();
            if (t.t == TT.LP) {
                consume();
                Node e = parseOr();
                if (peek().t != TT.RP) throw new IllegalArgumentException("缺 ) 在 " + peek().pos);
                consume();
                return e;
            }
            if (t.t == TT.NUM) {
                consume();
                // 无小数点的字面量按 long 精确解析(超 long 再回退 double)。
                // 科学计数法字面量(1e5)在此走 Long.parseLong 抛 NFE,
                // 由 catch 回退到下面的 Double.parseDouble(词法器产出的 NUM 可含 e/E,
                // 不做前置判断)
                if (t.s.indexOf('.') < 0) {
                    try { return new NumLit(Long.parseLong(t.s)); }
                    catch (NumberFormatException notALong) { /* 科学计数法/超 long → double */ }
                }
                return new NumLit(Double.parseDouble(t.s));
            }
            if (t.t == TT.STR) { consume(); return new StrLit(t.s); }
            if (t.t == TT.IDENT) {
                consume();
                if (t.s.equals("true")) return new BoolLit(true);
                if (t.s.equals("false")) return new BoolLit(false);
                if (t.s.equals("null")) return new NullLit();
                // 函数调用:nvl / coalesce / ifnull(空值函数,规范 07 §2.4)
                if (peek().t == TT.LP) {
                    consume();
                    List<Node> args = new ArrayList<>();
                    if (peek().t != TT.RP) {
                        args.add(parseOr());
                        while (peek().t == TT.COMMA) { consume(); args.add(parseOr()); }
                    }
                    if (peek().t != TT.RP) throw new IllegalArgumentException("函数缺 ')' 在 " + peek().pos);
                    consume();
                    return new FuncCall(t.s, args);
                }
                return new ColRef(t.s);
            }
            throw new IllegalArgumentException("意外的 token '" + t.s + "' 在 " + t.pos);
        }

        private Token peek() { return toks.get(pos); }
        private Token consume() { return toks.get(pos++); }
        private boolean isOp(String op) { Token t = peek(); return t.t == TT.OP && t.s.equals(op); }
        private boolean isKw(String kw) { Token t = peek(); return t.t == TT.IDENT && t.s.equalsIgnoreCase(kw); }
        private void expectKw(String kw) {
            if (!isKw(kw)) throw new IllegalArgumentException("期望 '" + kw + "' 实际 '" + peek().s + "' 在 " + peek().pos);
            consume();
        }
    }

    /**
     * 解析表达式为 AST。
     *
     * @param expr String 表达式文本,非 null
     * @return Node AST 根节点
     * @throws IllegalArgumentException 词法/语法错误时抛出
     */
    static Node parse(String expr) {
        return new Parser(new Lexer(expr).tokenize()).parse();
    }

    // ======================== Binding ========================

    @FunctionalInterface
    interface Binding { Object get(String name); }

    record RowBinding(DataFrame df, List<String> cols, int r) implements Binding {
    /**
     * @param name String 名称;非 null
     */
        public Object get(String name) {
            int idx = cols.indexOf(name);
            if (idx < 0) {
                throw new IllegalArgumentException("列 '" + name + "' 不存在,现有列:" + cols);
            }
            return df.get(r, idx);
        }
    }
}
