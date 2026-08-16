package jian.core;

// ┌─ What : LikePattern —— SQL LIKE 模式 → Java 正则(单一定义,防实现漂移)
// │  Why  : 全仓扫描发现 core 兜底解析器(SimpleQueryParser)与 jian-dsl 完整引擎
// │         (PrattEngine)各有一份**逐字符相同**的 likeToRegex —— LIKE 语义必须两处一致
// │         (否则兜底与完整引擎对同一 LIKE 表达式给出不同结果),按 §3.1.1.1 收敛为单一定义
// │  Who  : SimpleQueryParser(L1 兜底)/ PrattEngine.Like(L1 完整)的求值点
// │  When : SimpleQueryParser / PrattEngine 的 like 求值点调用时
// │  Where: jian-core/LikePattern.java
// │  How  : 数据走向:LIKE 模式字符串 → 逐字符映射 → Java 正则字符串(调用方 matches() 用)。
// │         逻辑路线(每字符三选一):
// │           路径 A('%')→ ".*"(任意串);
// │           路径 B('_')→ "."(单字符);
// │           路径 C(正则元字符 . * + ? ( ) [ ] { } ^ $ | \)→ 反斜杠转义为字面量(防正则注入);
// │           路径 D(其余)→ 原样。
/**
 * SQL LIKE 模式转 Java 正则:{@code %} 通配任意串、{@code _} 通配单字符、
 * 其余字符(含全部正则元字符)一律按字面量转义(防正则注入,OWASP 口径)。
 */
public final class LikePattern {

    private LikePattern() {}

    /**
     * LIKE 模式 → 正则字符串。
     * <pre>{@code
     * "北%"   → "北.*"
     * "a_c"  → "a.c"
     * "3.14" → "3\.14"   (点号字面量化,不匹配任意字符)
     * "50\\%" → "50%"    (反斜杠转义 \% \_ = 字面 %/_,SQL ESCAPE 语义)
     * }</pre>
     * @param pattern String LIKE 模式,非 null
     * @return String Java 正则(供 {@code str.matches()} 使用)
     */
    public static String toRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        char[] cs = pattern.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char ch = cs[i];
            // 路径 E:反斜杠转义 → \% 字面 %、\_ 字面 _、\\ 字面 \。
            // 让 like '50\%' 匹配字面 "50%" 而非把 % 当通配(SQL ESCAPE 语义,pandas 无此概念属超集)。
            if (ch == '\\' && i + 1 < cs.length && (cs[i + 1] == '%' || cs[i + 1] == '_' || cs[i + 1] == '\\')) {
                sb.append('\\').append(cs[i + 1]); i++; continue;
            }
            switch (ch) {
                case '%' -> sb.append(".*");
                case '_' -> sb.append('.');
                case '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> sb.append('\\').append(ch);
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
