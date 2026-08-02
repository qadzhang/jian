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
 * df.query() 的极简布尔表达式解析器(L1 子集)。
 *
 * <p>支持的语法子集(够用且明确,完整 SQL 子集见 jian-dsl):
 * <ul>
 *   <li>比较:{@code > < >= <= == !=}</li>
 *   <li>逻辑:{@code && || !}(也兼容 {@code and / or / not})</li>
 *   <li>字面量:数字(int/double)、字符串(单/双引号)、布尔、null</li>
 *   <li>列名:直接当标识符(从当前行 binding 取值)</li>
 *   <li>括号:任意嵌套</li>
 *   <li>谓词:{@code between X and Y} / {@code in (...)} / {@code is null} / {@code is not null} / {@code like 'A%'}</li>
 * </ul>
 *
 * <p><b>与 jian-dsl 的分工</b>:本类是 core 内置兜底(L1 子集),完整 L1+L2+L3(含 Pratt parser、SQL 子集、
 * ANTLR Oracle/PG/MySQL 多方言)在 jian-dsl 模块,core 经 SPI 可选加载。
 */
public final class SimpleQueryParser {

    private SimpleQueryParser() {}

    /**
     * 解析并求值表达式,返回每行的布尔掩码。
     *
     * @param df  目标 DataFrame
     * @param expr 表达式,如 {@code "age > 18 && city == 'SH'"}
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
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
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
                if (Character.isDigit(c) || (c == '-' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                    out.add(lexNumber()); continue;
                }
                if (Character.isLetter(c) || c == '_') { out.add(lexIdent()); continue; }
                String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                if (two.equals("&&") || two.equals("||") || two.equals(">=") || two.equals("<=")
                        || two.equals("==") || two.equals("!=")) {
                    out.add(new Token(TokType.OP, two, i)); i += 2; continue;
                }
                if (c == '>' || c == '<' || c == '!') {
                    out.add(new Token(TokType.OP, String.valueOf(c), i)); i++; continue;
                }
                throw new IllegalArgumentException("无法识别的字符 '" + c + "' 在位置 " + i);
            }
            out.add(new Token(TokType.END, "", i));
            return out;
        }
        private Token lexString() {
            char quote = src.charAt(i); int start = i; i++;
            StringBuilder sb = new StringBuilder();
            while (i < src.length() && src.charAt(i) != quote) {
                if (src.charAt(i) == '\\' && i + 1 < src.length()) { sb.append(src.charAt(i + 1)); i += 2; }
                else sb.append(src.charAt(i++));
            }
            if (i >= src.length()) throw new IllegalArgumentException("字符串未闭合,起始于 " + start);
            i++;  // skip closing quote
            return new Token(TokType.STR, sb.toString(), start);
        }
        private Token lexNumber() {
            int start = i;
            if (src.charAt(i) == '-') i++;
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
            return new Token(TokType.NUM, src.substring(start, i), start);
        }
        private Token lexIdent() {
            int start = i;
            while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
            return new Token(TokType.IDENT, src.substring(start, i), start);
        }
    }

    // ======================== AST 节点 ========================

    private sealed interface Node permits NumLit, StrLit, BoolLit, NullLit, ColRef, BinCmp, Logic, Not, Pred {
        Object eval(Binding b);
    }

    private record NumLit(double v) implements Node {
        @Override public Object eval(Binding b) { return v; }
    }
    private record StrLit(String v) implements Node {
        @Override public Object eval(Binding b) { return v; }
    }
    private record BoolLit(boolean v) implements Node {
        @Override public Object eval(Binding b) { return v; }
    }
    private record NullLit() implements Node {
        @Override public Object eval(Binding b) { return null; }
    }
    private record ColRef(String name, int pos) implements Node {
        @Override public Object eval(Binding b) { return b.get(name, pos); }
    }
    private record BinCmp(String op, Node l, Node r) implements Node {
        @Override public Object eval(Binding b) {
            Object a = l.eval(b), c = r.eval(b);
            if (a == null || c == null) return false;
            if (a instanceof Number && c instanceof Number) {
                double x = ((Number) a).doubleValue(), y = ((Number) c).doubleValue();
                return switch (op) { case ">" -> x > y; case "<" -> x < y; case ">=" -> x >= y;
                    case "<=" -> x <= y; case "==" -> x == y; case "!=" -> x != y; default -> throw new IllegalStateException(op); };
            }
            // 字符串等比较
            int cmp = String.valueOf(a).compareTo(String.valueOf(c));
            return switch (op) { case "==" -> a.equals(c); case "!=" -> !a.equals(c);
                case ">" -> cmp > 0; case "<" -> cmp < 0; case ">=" -> cmp >= 0; case "<=" -> cmp <= 0;
                default -> throw new IllegalStateException("运算符 " + op + " 不支持 " + a.getClass()); };
        }
    }
    private record Logic(String op, Node l, Node r) implements Node {
        @Override public Object eval(Binding b) {
            Object x = l.eval(b);
            if (op.equals("&&")) return toBool(x) && toBool(r.eval(b));
            return toBool(x) || toBool(r.eval(b));
        }
    }
    private record Not(Node e) implements Node {
        @Override public Object eval(Binding b) { return !toBool(e.eval(b)); }
    }
    private record Pred(String kind, Node col, Object extra) implements Node {
        @Override public Object eval(Binding b) {
            Object v = col.eval(b);
            return switch (kind) {
                case "isnull" -> v == null;
                case "notnull" -> v != null;
                case "between" -> {
                    Object[] range = (Object[]) extra;
                    if (v == null) yield false;
                    double dv = ((Number) v).doubleValue();
                    yield dv >= ((Number) range[0]).doubleValue() && dv <= ((Number) range[1]).doubleValue();
                }
                case "in", "notin" -> {
                    // 数据走向:col 值 v → 与 items 逐个比 → in 命中返回 true,notin 取反
                    Object[] items = (Object[]) extra;
                    boolean hit = false;
                    if (v != null) {
                        for (Object it : items) {
                            if (valueEquals(v, it)) { hit = true; break; }
                        }
                    }
                    yield kind.equals("in") ? hit : !hit;
                }
                case "like" -> {
                    // 安全:先把用户模式的全部正则元字符转义,只留 % _ 两个通配(防正则注入)
                    yield v != null && v.toString().matches(likeToRegex((String) extra));
                }
                default -> throw new IllegalStateException("未知谓词 " + kind);
            };
        }
    }

    /** LIKE 模式 → 正则:% 通配任意串,_ 通配单字符,其余字符(含正则元字符)一律字面量转义。 */
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

    /** 跨数值类型相等比较(Long 30 vs Double 30.0 视为相等)。 */
    private static boolean valueEquals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
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
            Node left = parsePrimary();
            // between / like / in / is [not] null
            if (peekIsKw("between")) {
                consume();
                Node lo = parsePrimary();
                expectKw("and");
                Node hi = parsePrimary();
                return new Pred("between", left, new Object[]{lo.eval(EMPTY), hi.eval(EMPTY)});
            }
            if (peekIsKw("like")) {
                consume();
                Token t = toks.get(pos);
                if (t.type != TokType.STR) throw new IllegalArgumentException("like 后需字符串字面量,位置 " + t.pos);
                pos++;
                return new Pred("like", left, t.text);
            }
            // in (...):字面量列表(支持 not in 前缀)
            boolean notIn = peekIsKw("not") && pos + 1 < toks.size()
                    && toks.get(pos + 1).type == TokType.IDENT
                    && toks.get(pos + 1).text.equalsIgnoreCase("in");
            if (peekIsKw("in") || notIn) {
                if (notIn) consume();  // 吃掉 not
                consume();             // 吃掉 in
                if (peek().type != TokType.LPAREN) throw new IllegalArgumentException("in 后需 ( ,位置 " + peek().pos);
                consume();
                List<Object> items = new ArrayList<>();
                while (peek().type != TokType.RPAREN) {
                    Node item = parsePrimary();
                    items.add(item.eval(EMPTY));
                    if (peek().type == TokType.COMMA) { consume(); continue; }
                    break;
                }
                if (peek().type != TokType.RPAREN) throw new IllegalArgumentException("in 缺 ')' ,位置 " + peek().pos);
                consume();
                return new Pred(notIn ? "notin" : "in", left, items.toArray());
            }
            if (peekIsKw("is")) {
                consume();
                boolean neg = false;
                if (peekIsKw("not")) { neg = true; consume(); }
                expectKw("null");
                return new Pred(neg ? "notnull" : "isnull", left, null);
            }
            // 普通 < > >= <= == !=
            if (peek().type == TokType.OP && isCmpOp(peek().text)) {
                String op = consume().text;
                Node right = parsePrimary();
                return new BinCmp(op, left, right);
            }
            return left;
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
            if (t.type == TokType.NUM) { consume(); return new NumLit(Double.parseDouble(t.text)); }
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
        private static boolean isCmpOp(String s) {
            return s.equals(">") || s.equals("<") || s.equals(">=") || s.equals("<=")
                    || s.equals("==") || s.equals("!=");
        }
    }

    private static final Binding EMPTY = (name, pos) -> null;

    @FunctionalInterface
    private interface Binding { Object get(String name, int pos); }

    /** 单行 binding:列名 → 第 r 行的值。 */
    private record RowBinding(DataFrame df, List<String> colNames, int r) implements Binding {
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
