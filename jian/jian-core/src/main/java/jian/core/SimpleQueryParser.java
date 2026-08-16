package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : SimpleQueryParser —— df.query() 的极简布尔表达式解析器(L1 子集,对齐 pandas query 的最常用部分)
// │  Why  : 规范 01 §3.3 要求 query;core 内置极简版(支持 && || 比较 字面量),完整 SQL 子集在 jian-dsl(M6)
// │  Who  : 由 DataFrame.query 调用,返回布尔掩码
// │  When : df.query("age > 18 && city == 'SH'") 等过滤场景
// │  Where: jian-core/SimpleQueryParser.java
// │  How  : 数据走向:表达式字符串 → 词法分析(Lexer)→ Token 流 → 递归下降解析 → AST → 逐行求值 → bool[] 掩码。
// │         关键变量变化:
// │           - tokens:Lexer 产出的 Token 列表;
// │           - pos:解析位置游标;
// │           - ast:表达式 AST(根节点)。
// │         逻辑路线(三阶段):
// │           阶段1 Lexer:字符串 → Token(数字/字符串/标识符/运算符);
// │           阶段2 Parser(递归下降,优先级 || < && < ! < 比较):
//             阶段3 Evaluator:对每行 binding(列名 → 值)求值,返回 bool[]。
// │           路径 A(语法错)→ 抛 IllegalArgumentException 带位置;
// │           路径 B(列不存在/类型不兼容)→ 求值时抛 IllegalArgumentException 带提示;
// │           路径 C(正常)→ 返回长度 = 行数的 bool[]。
/**
 * df.query() 的极简布尔表达式解析器(L1 子集,对齐 pandas query 的最常用部分)。
 *
 * <p>支持的语法子集(与 jian-dsl PrattEngine 口径对齐):
 * <ul>
 *   <li>比较:{@code > < >= <= == !=}</li>
 *   <li>逻辑:{@code && || !}(也兼容 {@code and / or / not});数值不再隐式当布尔(非零即 true 已移除,
 *       对齐 pandas/numexpr fail-fast,双引擎同步)</li>
 *   <li>算术:{@code + - * / %}(一元负号;字符串 {@code +} 字符串拼接)</li>
 *   <li>字面量:数字(int/double,含科学计数法)、字符串(单/双引号;支持 {@code ''} 翻倍与
 *       {@code \' \\ \" \n \t} 反斜杠转义,三种写法等价)、布尔、null</li>
 *   <li>列名:直接当标识符(Unicode 中文等可用);特殊字符列名用反引号
 *       {@code `col with space`}(pandas query 同款)</li>
 *   <li>括号:任意嵌套</li>
 *   <li>谓词:{@code between X and Y} / {@code in (...)}(含 {@code not in} 与单字 {@code notin},
 *       列表元素支持列引用,行级求值)/ {@code is [not] null} / {@code is [not] true|false}(SQL 风格超集)
 *       / {@code like 'A%'}(pattern 内 {@code \%} {@code \_} 为字面转义)</li>
 * </ul>
 *
 * <p><b>与 jian-dsl 的分工</b>:本类是 core 内置兜底(L1 子集),完整 L1+L2+L3(含 Pratt parser、SQL 子集、
 * ANTLR Oracle/PG/MySQL 多方言)在 jian-dsl 模块,core 经 SPI 可选加载。两引擎语法矩阵由
 * {@code EngineConformanceTest} 保证一致。
 */
public final class SimpleQueryParser {

    private SimpleQueryParser() {}

    /**
     * 解析并求值表达式,返回每行的布尔掩码。
     *
     * @param df   DataFrame 目标表,非 null;表达式中的标识符须是该表的列名
     * @param expr String 布尔表达式,如 {@code "age > 18 && city == 'SH'"};支持比较/逻辑/括号/in/between/like/is null;非 null
     * @return boolean[] 长度 == df.rowCount();每行表达式求值结果 true/false
     * @throws IllegalArgumentException 表达式语法错(消息含位置),或列名不存在/类型不兼容
     */
    public static boolean[] evaluate(DataFrame df, String expr) {
        List<Token> tokens = new Lexer(expr).tokenize();
        Parser parser = new Parser(tokens, expr);
        Node ast = parser.parseExpression();
        parser.expectEnd();
        // 逐行求值
        boolean[] mask = new boolean[df.rowCount()];
        List<String> colNames = df.columnNames();
        for (int r = 0; r < df.rowCount(); r++) {
            mask[r] = toBool(ast.eval(new RowBinding(df, colNames, r)));
        }
        return mask;
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        // 因为数值隐式当布尔(非零即 true)会掩盖逻辑 bug,且 pandas/numexpr 对数值做
        // 逻辑运算符直接语法错,所以这里对齐 fail-fast 抛错(与 PrattEngine 同步)。
        if (v instanceof Number) {
            throw new IllegalArgumentException("逻辑运算符要求布尔操作数,实际 "
                    + v.getClass().getSimpleName() + " (" + v + ");如需判空请用 is null/非零请显式 == 0 判断(对齐 pandas)");
        }
        throw new IllegalArgumentException("表达式最终结果非布尔:" + v.getClass().getSimpleName());
    }

    // ======================== 词法 ========================

    enum TokType { NUM, STR, IDENT, OP, LPAREN, RPAREN, COMMA, END }

    static final class Token {
        final TokType type; final String text; final int pos;
        Token(TokType t, String s, int p) { type = t; text = s; pos = p; }
    }

    static final class Lexer {
        private final String src;
        private int i = 0;
        Lexer(String s) { this.src = s; }
    List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '(') { out.add(new Token(TokType.LPAREN, "(", i++)); continue; }
                if (c == ')') { out.add(new Token(TokType.RPAREN, ")", i++)); continue; }
                if (c == ',') { out.add(new Token(TokType.COMMA, ",", i++)); continue; }
                if (c == '\'' || c == '"') { out.add(lexString()); continue; }
                // 算术支持。因为要区分一元负号与二元减,所以负号仅在一元语境
                // (开头/运算符/左括号/逗号后)并入数字字面量,其余语境('-' 在两个操作数之间)
                // 单独成 OP,交给 parseAddSub 做二元减。
                if (Character.isDigit(c) || (c == '-' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))
                        && (out.isEmpty() || lastIsValueBoundary(out)))) {
                    out.add(lexNumber()); continue;
                }
                // 反引号标识符(`col with space`,pandas query 同款)。
                // 内容整体作 IDENT,不再做词法分析,列名可含空格/点/减号/中文等任意字符(反引号本身除外)。
                if (c == '`') { out.add(lexBacktickIdent()); continue; }
                if (Character.isLetter(c) || c == '_') { out.add(lexIdent()); continue; }
                String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                if (two.equals("&&") || two.equals("||") || two.equals(">=") || two.equals("<=")
                        || two.equals("==") || two.equals("!=")) {
                    out.add(new Token(TokType.OP, two, i)); i += 2; continue;
                }
                if (c == '>' || c == '<' || c == '!' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%') {
                    out.add(new Token(TokType.OP, String.valueOf(c), i)); i++; continue;
                }
                throw new IllegalArgumentException("无法识别的字符 '" + c + "' 在位置 " + i);
            }
            out.add(new Token(TokType.END, "", i));
            return out;
        }
        /** 一元语境判定:前一 token 是 OP/左括号/逗号(或 token 流为空)时,'-数字' 是负数字面量。 */
        private static boolean lastIsValueBoundary(List<Token> out) {
            TokType t = out.get(out.size() - 1).type;
            return t == TokType.OP || t == TokType.LPAREN || t == TokType.COMMA;
        }
        /**
         * 词法:反引号标识符。`...` 内容原样作为 IDENT 文本。
         * 数据走向:backtick 起始 → 逐字符拷贝(不识别任何转义)→ 闭合 backtick → IDENT token。
         * 逻辑路线:路径 A(未闭合)→ 抛 IAE 带起始位置;路径 B(正常)→ 返回 IDENT。
         */
        private Token lexBacktickIdent() {
            int start = i; i++;
            StringBuilder sb = new StringBuilder();
            while (i < src.length() && src.charAt(i) != '`') sb.append(src.charAt(i++));
            if (i >= src.length()) throw new IllegalArgumentException("反引号标识符未闭合,起始于 " + start);
            i++;
            return new Token(TokType.IDENT, sb.toString(), start);
        }
        /**
         * 词法:读取字符串字面量(单/双引号)。
         * 支持三种等价转义——
         *   ① ANSI SQL 单引号翻倍:'It''s' → It's(与 jian-dsl PrattEngine/normalizeSqlExpr 口径一致);
         *   ② 反斜杠常见转义:\\ \' \" \n \t;
         *   ③ like 场景:\% \_ 保留双字符原样(交由 LikePattern.toRegex 解析为字面 %/_),
         *      其余 \x 亦保留双字符字面(不吞字符,与 PrattEngine 反斜杠不特殊处理的保守取向兼容)。
         */
        private Token lexString() {
            char quote = src.charAt(i); int start = i; i++;
            StringBuilder sb = new StringBuilder();
            while (i < src.length()) {
                char c = src.charAt(i);
                // 闭合判定(先于翻倍:遇引号时若下一字符同为该引号 → ANSI 翻倍转义,否则字符串结束)
                if (c == quote) {
                    if (i + 1 < src.length() && src.charAt(i + 1) == quote) {
                        sb.append(quote); i += 2; continue;
                    }
                    i++;
                    return new Token(TokType.STR, sb.toString(), start);
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
        /**
         * 词法:读取数字字面量(含科学计数法)。
         * 支持 -?digits(.digits)?([eE][+-]?digits)?
         * 回退规则:e 后必须跟数字才算指数,否则回退(e 交给 lexIdent,
         * 如 "1e" 场景:1 为 NUM、e 为 IDENT,表达式自然报语法错)。
         */
        private Token lexNumber() {
            int start = i;
            if (src.charAt(i) == '-') i++;
            int dots = 0;
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                if (src.charAt(i) == '.') dots++;
                i++;
            }
            // 因为 Double.parseDouble 对多小数点(如 1.2.3)抛裸 NumberFormatException 丢失位置信息,
            // 所以在词法层即报错带位置。
            if (dots > 1) {
                throw new IllegalArgumentException("数字字面量含多个小数点 '" + src.substring(start, i) + "' 在位置 " + start);
            }
            // 科学计数法:1e10 / 1.5e-3 / 1E+10(e 后必须跟数字,否则回退)
            if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
                if (i < src.length() && Character.isDigit(src.charAt(i))) {
                    while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
                } else {
                    i = save;   // 回退:e 后无数字,不消费,e 交给 lexIdent
                }
            }
            return new Token(TokType.NUM, src.substring(start, i), start);
        }
        private Token lexIdent() {
            int start = i;
            while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
            return new Token(TokType.IDENT, src.substring(start, i), start);
        }
    }

    // ======================== AST 节点 ========================

    private sealed interface Node permits NumLit, StrLit, BoolLit, NullLit, ColRef, BinCmp, Arith, Logic, Not, Pred {
        Object eval(Binding b);
    }
    /**
     * 算术节点(对齐 pandas query 与 PrattEngine)。
     * 数据走向:左右操作数求值 → null 传播(任一缺失返 null,对齐 §3.5)→ 双数值按 double 运算 →
     * 字符串 + 字符串走拼接(pandas 'a'+'b' 同款;其余混型组合抛 IAE)。
     * 逻辑路线:路径 A(null 参与算术)→ 返 null(缺失传播);路径 B(双 Number)→ 运算结果 Double;
     * 路径 C(String+String 且 op='+')→ 拼接;路径 D(其它混型)→ 抛 IAE 带类型提示。
     */
    private record Arith(String op, Node l, Node r) implements Node {
        /** @param b Binding 变量绑定,非 null */
        @Override public Object eval(Binding b) {
            Object a = l.eval(b), c = r.eval(b);
            if (a == null || c == null) return null;
            if (a instanceof Number && c instanceof Number) {
                double x = ((Number) a).doubleValue(), y = ((Number) c).doubleValue();
                return switch (op) {
                    case "+" -> x + y; case "-" -> x - y; case "*" -> x * y;
                    case "/" -> x / y; case "%" -> x % y;
                    default -> throw new IllegalStateException(op);
                };
            }
            if (op.equals("+") && a instanceof String && c instanceof String) return (String) a + c;
            throw new IllegalArgumentException("算术运算 '" + op + "' 要求双数值(或字符串 + 字符串拼接),实际 "
                    + a.getClass().getSimpleName() + " 与 " + c.getClass().getSimpleName());
        }
    }

    private record NumLit(Number v) implements Node {
        // 因为整数字面量装 Long 才能精确比较(与 PrattEngine.NumLit 双引擎同步),
        // 所以 NumLit 持有 Number 而非 double
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) { return v; }
    }
    private record StrLit(String v) implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) { return v; }
    }
    private record BoolLit(boolean v) implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) { return v; }
    }
    private record NullLit() implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) { return null; }
    }
    private record ColRef(String name, int pos) implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) { return b.get(name, pos); }
    }
    private record BinCmp(String op, Node l, Node r) implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) {
            Object a = l.eval(b), c = r.eval(b);
            // null(缺失行)对齐 pandas NaN 传播:
            //   == / > < >= <= → false;!= → true(对齐 NaN != x 为 True)。详见 §10.16。
            if (a == null || c == null) {
                return switch (op) { case "!=" -> true; default -> false; };
            }
            if (a instanceof Number && c instanceof Number) {
                // 因为一律 doubleValue 时,>2^53 的 long 字面量(如 9223372036854775806)
                // 与列值都被舍入到 2^63 误匹配,所以双整数走 long 精确比较。
                // NumLit 已把整数字面量装 Long,此处与 PrattEngine 同步用 DataFrameCompare.cmp
                //(双整数 long 精确路径 + 混型对齐 pandas 契约)。
                return DataFrameCompare.cmp(a, op, c);
            }
            if (a instanceof String && c instanceof String) {
                int cmp = ((String) a).compareTo((String) c);
                return switch (op) { case "==" -> a.equals(c); case "!=" -> !a.equals(c);
                    case ">" -> cmp > 0; case "<" -> cmp < 0; case ">=" -> cmp >= 0; case "<=" -> cmp <= 0;
                    default -> throw new IllegalStateException(op); };
            }
            // 相等/不等:任意类型可用(不同类型自然不等)
            if (op.equals("==")) return a.equals(c);
            if (op.equals("!=")) return !a.equals(c);
            // 顺序比较(> < >= <=):类型不兼容直接抛错。
            // 因为 String.valueOf(a).compareTo(...) 会把 "abc" > 1 静默当字符串比较返回 true
            // (对齐 pandas 抛 TypeError),所以这里改抛 IAE 带类型提示。
            throw new IllegalArgumentException("无法比较 " + a.getClass().getSimpleName()
                    + " 与 " + c.getClass().getSimpleName() + "(运算符 " + op + ")");
        }
    }
    private record Logic(String op, Node l, Node r) implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) {
            Object x = l.eval(b);
            if (op.equals("&&")) return toBool(x) && toBool(r.eval(b));
            return toBool(x) || toBool(r.eval(b));
        }
    }
    private record Not(Node e) implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) { return !toBool(e.eval(b)); }
    }
    private record Pred(String kind, Node col, Object extra) implements Node {
    /**
     * @param b Boolean 布尔值
     */
        @Override public Object eval(Binding b) {
            Object v = col.eval(b);
            return switch (kind) {
                // 因为 DOUBLE 列缺失在 get 层是 Double.NaN(§3.5 不失真),
                // 所以 is null 须把 NaN 当缺失(core 兜底引擎与 PrattEngine 口径对齐,
                // 否则同一表达式两引擎结果不同 —— EngineConformanceTest 锁定)。
                case "isnull" -> v == null || (v instanceof Double d && d.isNaN());
                case "notnull" -> !(v == null || (v instanceof Double d && d.isNaN()));
                case "between" -> {
                    // lo/hi 为 Node,行级求值(支持列到列 between)
                    Node[] range = (Node[]) extra;
                    if (v == null) yield false;
                    if (!(v instanceof Number)) {
                        throw new IllegalArgumentException("between 只支持数值列,实际列值类型 " + v.getClass().getSimpleName());
                    }
                    Object loV = range[0].eval(b), hiV = range[1].eval(b);
                    if (loV == null || hiV == null) yield false;
                    if (!(loV instanceof Number) || !(hiV instanceof Number)) {
                        throw new IllegalArgumentException("between 边界须为数值,实际 " + loV + " 与 " + hiV);
                    }
                    double dv = ((Number) v).doubleValue();
                    yield dv >= ((Number) loV).doubleValue() && dv <= ((Number) hiV).doubleValue();
                }
                case "in", "notin" -> {
                    // 数据走向:col 值 v → 与 items 逐个【行级】求值比较 → in 命中返回 true,notin 取反。
                    // 因为 parse 期提前求值会让列引用 in (col_a, col_b) 被静默求为 null 永不命中,
                    // 所以 items 存 Node[](与 between 同为行级惰性求值)。
                    Node[] items = (Node[]) extra;
                    boolean hit = false;
                    if (v != null) {
                        for (Node itemNode : items) {
                            if (DataFrameCompare.valueEquals(v, itemNode.eval(b))) { hit = true; break; }
                        }
                    }
                    yield kind.equals("in") ? hit : !hit;
                }
                // is [not] true / is [not] false,SQL 三值逻辑:
                // null 值 → is true/is false 均 false;is not true 对 false 与 null 均 true。
                case "bool-true", "notbool-true", "bool-false", "notbool-false" -> {
                    boolean wantTrue = kind.endsWith("true");
                    boolean neg = kind.startsWith("notbool");
                    boolean eq = v instanceof Boolean bo && bo == wantTrue;
                    yield neg ? !eq : eq;
                }
                case "like" -> {
                    // 安全:先把用户模式的全部正则元字符转义,只留 % _ 两个通配(防正则注入)
                    yield v != null && v.toString().matches(LikePattern.toRegex((String) extra));
                }
                default -> throw new IllegalStateException("未知谓词 " + kind);
            };
        }
    }

    // ======================== 解析器(递归下降)========================

    static final class Parser {
        private final List<Token> toks; private final String expr; private int pos = 0;
        Parser(List<Token> t, String e) { toks = t; expr = e; }

        Node parseExpression() { return parseOr(); }

        // 优先级:|| → && → ! → 比较/谓词 → primary
        private Node parseOr() {
            Node left = parseAnd();
            while (peekIsOp("||") || peekIsKw("or")) {
                consume();
                Node right = parseAnd();
                left = new Logic("||", left, right);
            }
            return left;
        }
        private Node parseAnd() {
            Node left = parseNot();
            while (peekIsOp("&&") || peekIsKw("and")) {
                consume();
                Node right = parseNot();
                left = new Logic("&&", left, right);
            }
            return left;
        }
        private Node parseNot() {
            if (peekIsOp("!") || peekIsKw("not")) {
                consume();
                return new Not(parseNot());
            }
            return parseComparison();
        }
        private Node parseComparison() {
            Node left = parseAddSub();
            // between / like / in / is [not] null
            // 双引擎一致性:not like / not between 前缀取反(PrattEngine 同款;
            // EngineConformanceTest 锁定两引擎语法矩阵一致)
            boolean negPred = false;
            if (peekIsKw("not") && pos + 1 < toks.size() && toks.get(pos + 1).type == TokType.IDENT
                    && (toks.get(pos + 1).text.equalsIgnoreCase("like")
                        || toks.get(pos + 1).text.equalsIgnoreCase("between"))) {
                consume();
                negPred = true;
            }
            if (peekIsKw("between")) {
                consume();
                Node lo = parseAddSub();
                expectKw("and");
                Node hi = parseAddSub();
                // 因为存 Node 延迟到行级求值可支持列到列 between
                // ("between age and age2" 若 parse 期求值会对列引用返回 null 而 NPE),
                // 所以这里存 Node 而非提前求好的值
                Pred p = new Pred("between", left, new Node[]{lo, hi});
                return negPred ? new Not(p) : p;
            }
            if (peekIsKw("like")) {
                consume();
                Token t = toks.get(pos);
                if (t.type != TokType.STR) throw new IllegalArgumentException("like 后需字符串字面量,位置 " + t.pos);
                pos++;
                Pred p = new Pred("like", left, t.text);
                return negPred ? new Not(p) : p;
            }
            // in (...):字面量/表达式列表(支持 not in 前缀;也支持 pandas 的 notin 单字)
            // 按分支精确消费 token:
            //   路径 A(单字 notin)→ 只吃 1 个 token;路径 B(not + in 两词)→ 吃 2 个;路径 C(裸 in)→ 1 个。
            // 因为 parse 期提前求值会让列引用 in (col_a, col_b) 被静默求为 null 永不命中,
            // 所以列表元素保留 Node 延迟到行级求值。
            boolean notIn = peekIsKw("notin")
                || (peekIsKw("not") && pos + 1 < toks.size()
                    && toks.get(pos + 1).type == TokType.IDENT
                    && toks.get(pos + 1).text.equalsIgnoreCase("in"));
            if (peekIsKw("in") || notIn) {
                if (peekIsKw("notin")) { consume(); }        // 路径 A:吃 notin 单字
                else if (notIn) { consume(); consume(); }    // 路径 B:吃 not + in 两词
                else { consume(); }                          // 路径 C:裸 in,吃 1 个
                if (peek().type != TokType.LPAREN) throw new IllegalArgumentException("in 后需 ( ,位置 " + peek().pos);
                consume();
                List<Node> itemNodes = new ArrayList<>();
                while (peek().type != TokType.RPAREN) {
                    itemNodes.add(parseAddSub());
                    if (peek().type == TokType.COMMA) { consume(); continue; }
                    break;
                }
                if (peek().type != TokType.RPAREN) throw new IllegalArgumentException("in 缺 ')' ,位置 " + peek().pos);
                consume();
                return new Pred(notIn ? "notin" : "in", left, itemNodes.toArray(new Node[0]));
            }
            if (peekIsKw("is")) {
                consume();
                boolean neg = false;
                if (peekIsKw("not")) { neg = true; consume(); }
                // is true / is false(SQL 风格,pandas 无此语法属超集增强)。
                // 语义对齐 SQL 三值逻辑:null is true → false;flag is not true 对 false 与 null 均 true。
                if (peekIsKw("true")) { consume(); return new Pred(neg ? "notbool-true" : "bool-true", left, null); }
                if (peekIsKw("false")) { consume(); return new Pred(neg ? "notbool-false" : "bool-false", left, null); }
                expectKw("null");
                return new Pred(neg ? "notnull" : "isnull", left, null);
            }
            // 普通 < > >= <= == !=
            if (peek().type == TokType.OP && DataFrameCompare.isCmpOp(peek().text)) {
                String op = consume().text;
                Node right = parseAddSub();
                return new BinCmp(op, left, right);
            }
            return left;
        }
        // + - (算术优先级介于比较与乘除之间,对齐 pandas/PrattEngine)
        private Node parseAddSub() {
            Node left = parseMulDiv();
            while (peek().type == TokType.OP && (peek().text.equals("+") || peek().text.equals("-"))) {
                String op = consume().text;
                left = new Arith(op, left, parseMulDiv());
            }
            return left;
        }
        // * / %
        private Node parseMulDiv() {
            Node left = parseUnary();
            while (peek().type == TokType.OP
                    && (peek().text.equals("*") || peek().text.equals("/") || peek().text.equals("%"))) {
                String op = consume().text;
                left = new Arith(op, left, parseUnary());
            }
            return left;
        }
        // 一元 - / +
        private Node parseUnary() {
            if (peek().type == TokType.OP && (peek().text.equals("-") || peek().text.equals("+"))) {
                String op = consume().text;
                Node inner = parseUnary();
                return op.equals("-") ? new Arith("*", new NumLit(-1L), inner) : inner;
            }
            return parsePrimary();
        }
        private Node parsePrimary() {
            Token t = peek();
            if (t.type == TokType.LPAREN) {
                consume();
                Node e = parseExpression();
                if (peek().type != TokType.RPAREN) throw new IllegalArgumentException("缺少 ')',位置 " + peek().pos);
                consume();
                return e;
            }
            if (t.type == TokType.NUM) {
                consume();
                // 纯整数字面量按 long 精确解析,超 long 抛 IAE(fail-fast,与 PrattEngine
                // 双引擎同步):静默回退 double 会把超出值折成最近 double,与 LONG 列值的
                // double 投影恰好相等,> / == 误匹配;近似需求请显式写科学计数法(1e19)
                if (t.text.indexOf('.') < 0 && t.text.indexOf('e') < 0 && t.text.indexOf('E') < 0) {
                    try { return new NumLit(Long.parseLong(t.text)); }
                    catch (NumberFormatException overflow) {
                        throw new IllegalArgumentException(
                            "整数子面量超出 long 范围:" + t.text
                            + "(jian 不支持任意精度整数字面量;如需近似比较请改写为科学计数法,如 9.22e18)");
                    }
                }
                return new NumLit(Double.parseDouble(t.text));
            }
            if (t.type == TokType.STR) { consume(); return new StrLit(t.text); }
            if (t.type == TokType.IDENT) {
                consume();
                if (t.text.equals("true")) return new BoolLit(true);
                if (t.text.equals("false")) return new BoolLit(false);
                if (t.text.equals("null")) return new NullLit();
                return new ColRef(t.text, t.pos);
            }
            throw new IllegalArgumentException("意外的 token '" + t.text + "' 在位置 " + t.pos);
        }

        private Token peek() { return toks.get(pos); }
        private Token consume() { return toks.get(pos++); }
        private boolean peekIsOp(String op) {
            Token t = peek(); return t.type == TokType.OP && t.text.equals(op);
        }
        private boolean peekIsKw(String kw) {
            Token t = peek(); return t.type == TokType.IDENT && t.text.equalsIgnoreCase(kw);
        }
        private void expectKw(String kw) {
            if (!peekIsKw(kw)) throw new IllegalArgumentException("期望 '" + kw + "',实际 '" + peek().text + "' 位置 " + peek().pos);
            consume();
        }
        private void expectEnd() {
            if (peek().type != TokType.END)
                throw new IllegalArgumentException("表达式尾部多余 token '" + peek().text + "' 位置 " + peek().pos);
        }
    }

    @FunctionalInterface
    private interface Binding { Object get(String name, int pos); }

    /** 单行 binding:列名 → 第 r 行的值。 */
    private record RowBinding(DataFrame df, List<String> colNames, int r) implements Binding {
    /**
     * @param name String 名称;非 null
     * @param pos int 位置
     */
        @Override public Object get(String name, int pos) {
            int idx = colNames.indexOf(name);
            if (idx < 0) {
                throw new IllegalArgumentException("列 '" + name + "' 不存在(位置 " + pos
                        + "),现有列:" + colNames);
            }
            return df.get(r, idx);
        }
    }
}
