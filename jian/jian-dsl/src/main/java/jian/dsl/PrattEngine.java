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

    /** L1 query:对每行求值 expr,返回满足的行组成新 DataFrame。 */
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

    /** L2 eval:派生新列(支持 `name = expr; name2 = expr2` 多语句)。 */
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

    /** 展开 ${name} 占位 → 字面量(JSON-ish 形式:字符串加引号,数字直接,空保留 null)。 */
    static String expandParams(String expr, Params params) {
        if (params == null || params == Params.EMPTY) return expr;
        Matcher m = Pattern.compile("\\$\\{(\\w+)\\}").matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = params.get(key);
            String rep;
            if (val == null) rep = "null";
            else if (val instanceof Number) rep = val.toString();
            else if (val instanceof Boolean) rep = val.toString();
            else rep = "'" + val.toString().replace("'", "\\'") + "'";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        throw new IllegalArgumentException("表达式最终结果非布尔:" + v);
    }

    // ======================== AST ========================

    sealed interface Node permits NumLit, StrLit, BoolLit, NullLit, ColRef, BinOp, Logic, Not, Ternary, Between, Like, In, IsNull, FuncCall {
        Object eval(Binding b);
    }

    record NumLit(double v) implements Node {
        public Object eval(Binding b) { return v; }
    }
    record StrLit(String v) implements Node {
        public Object eval(Binding b) { return v; }
    }
    record BoolLit(boolean v) implements Node {
        public Object eval(Binding b) { return v; }
    }
    record NullLit() implements Node {
        public Object eval(Binding b) { return null; }
    }
    record ColRef(String name) implements Node {
        public Object eval(Binding b) { return b.get(name); }
    }
    record BinOp(String op, Node l, Node r) implements Node {
        public Object eval(Binding b) {
            Object a = l.eval(b), c = r.eval(b);
            if (op.equals("+") && (a instanceof String || c instanceof String)) {
                return String.valueOf(a) + String.valueOf(c);
            }
            if (a instanceof Number && c instanceof Number) {
                double x = ((Number) a).doubleValue(), y = ((Number) c).doubleValue();
                return switch (op) {
                    case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y; case "%" -> x % y;
                    case ">" -> x > y; case "<" -> x < y; case ">=" -> x >= y; case "<=" -> x <= y;
                    case "==" -> x == y; case "!=" -> x != y;
                    default -> throw new IllegalStateException(op);
                };
            }
            // 字符串比较
            int cmp = String.valueOf(a).compareTo(String.valueOf(c));
            return switch (op) {
                case "==" -> a == null ? c == null : a.equals(c);
                case "!=" -> a == null ? c != null : !a.equals(c);
                case ">" -> cmp > 0; case "<" -> cmp < 0; case ">=" -> cmp >= 0; case "<=" -> cmp <= 0;
                default -> throw new IllegalStateException("运算符 " + op + " 不支持 " + (a == null ? "null" : a.getClass()));
            };
        }
    }
    record Logic(String op, Node l, Node r) implements Node {
        public Object eval(Binding b) {
            if (op.equals("&&")) return toBool(l.eval(b)) && toBool(r.eval(b));
            return toBool(l.eval(b)) || toBool(r.eval(b));
        }
    }
    record Not(Node e) implements Node {
        public Object eval(Binding b) { return !toBool(e.eval(b)); }
    }
    record Ternary(Node c, Node a, Node b) implements Node {
        public Object eval(Binding bnd) {
            return toBool(c.eval(bnd)) ? a.eval(bnd) : b.eval(bnd);
        }
    }
    record Between(Node e, Node lo, Node hi) implements Node {
        public Object eval(Binding b) {
            Object v = e.eval(b);
            if (v == null) return false;
            double x = ((Number) v).doubleValue();
            return x >= ((Number) lo.eval(b)).doubleValue() && x <= ((Number) hi.eval(b)).doubleValue();
        }
    }
    record Like(Node e, String pattern) implements Node {
        public Object eval(Binding b) {
            Object v = e.eval(b);
            if (v == null) return false;
            // 安全:只把 % _ 当通配,其余正则元字符一律转义(防正则注入)
            return v.toString().matches(likeToRegex(pattern));
        }
    }

    /** 函数调用(空值函数 nvl/coalesce/ifnull,规范 07 §2.4)。 */
    record FuncCall(String name, List<Node> args) implements Node {
        public Object eval(Binding b) {
            String fn = name.toLowerCase();
            if (fn.equals("nvl") || fn.equals("coalesce") || fn.equals("ifnull")) {
                // 空值函数:返回第一个非 null 参数(全 null 返回 null);参数可引用列,逐行求值
                for (Node a : args) {
                    Object v = a.eval(b);
                    if (v != null) return v;
                }
                return null;
            }
            throw new IllegalArgumentException("未知函数 '" + name + "',支持:nvl / coalesce / ifnull");
        }
    }

    /** LIKE 模式 → 正则:% 任意串,_ 单字符,其余字符字面量(防正则注入)。 */
    private static String likeToRegex(String pat) {
        StringBuilder sb = new StringBuilder();
        for (char ch : pat.toCharArray()) {
            switch (ch) {
                case '%' -> sb.append(".*");
                case '_' -> sb.append('.');
                case '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> sb.append('\\').append(ch);
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
    record In(Node e, List<Node> items) implements Node {
        public Object eval(Binding b) {
            Object v = e.eval(b);
            for (Node n : items) {
                Object item = n.eval(b);
                if (v == null) { if (item == null) return true; }
                else if (valueEquals(v, item)) return true;
            }
            return false;
        }
    }

    /** 跨数值类型相等比较(Long 30 vs Double 30.0 视为相等)。 */
    private static boolean valueEquals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }
    record IsNull(Node e, boolean negate) implements Node {
        public Object eval(Binding b) {
            Object v = e.eval(b);
            return negate ? v != null : v == null;
        }
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
                if (Character.isDigit(c) || (c == '-' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))
                        && (out.isEmpty() || out.get(out.size() - 1).t == TT.OP || out.get(out.size() - 1).t == TT.LP
                            || out.get(out.size() - 1).t == TT.COMMA))) {
                    out.add(number()); continue;
                }
                if (Character.isLetter(c) || c == '_') { out.add(ident()); continue; }
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
        private Token string() {
            char q = src.charAt(i); int start = i; i++;
            StringBuilder sb = new StringBuilder();
            while (i < src.length() && src.charAt(i) != q) {
                if (src.charAt(i) == '\\' && i + 1 < src.length()) { sb.append(src.charAt(i + 1)); i += 2; }
                else sb.append(src.charAt(i++));
            }
            if (i >= src.length()) throw new IllegalArgumentException("字符串未闭合,起始于 " + start);
            i++;
            return new Token(TT.STR, sb.toString(), start);
        }
        private Token number() {
            int start = i;
            if (src.charAt(i) == '-') i++;
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
            return new Token(TT.NUM, src.substring(start, i), start);
        }
        private Token ident() {
            int start = i;
            while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
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
            if (isKw("in")) {
                consume();
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
            if (isKw("is")) {
                consume();
                boolean neg = false;
                if (isKw("not")) { neg = true; consume(); }
                expectKw("null");
                return new IsNull(left, neg);
            }
            if (peek().t == TT.OP && isCmp(peek().s)) {
                String op = consume().s;
                return new BinOp(op, left, parseAddSub());
            }
            return left;
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
            if (isOp("-")) { consume(); return new BinOp("*", new NumLit(-1), parseUnary()); }
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
            if (t.t == TT.NUM) { consume(); return new NumLit(Double.parseDouble(t.s)); }
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
        private static boolean isCmp(String s) {
            return s.equals(">") || s.equals("<") || s.equals(">=") || s.equals("<=") || s.equals("==") || s.equals("!=");
        }
    }

    static Node parse(String expr) {
        return new Parser(new Lexer(expr).tokenize()).parse();
    }

    // ======================== Binding ========================

    @FunctionalInterface
    interface Binding { Object get(String name); }

    record RowBinding(DataFrame df, List<String> cols, int r) implements Binding {
        public Object get(String name) {
            int idx = cols.indexOf(name);
            if (idx < 0) {
                throw new IllegalArgumentException("列 '" + name + "' 不存在,现有列:" + cols);
            }
            return df.get(r, idx);
        }
    }
}
