package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import jian.dsl.Params;
import jian.dsl.SqlEngines;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : SecurityAuditTest —— 两种部署形态(本地 jar / Tomcat·Spring Boot)的安全行为锁
// │  Why  : 按 ai-code-testing Layer 2,
// │         每个防护不只靠"代码看起来对",必须用测试锁住行为(改坏即红)
// │  Who  : mvn -pl jian-facade test
// │  When : mvn test(jian-facade 模块)
// │  Where: jian-facade/src/test/java/jian/scenario/SecurityAuditTest.java
// │  How  : 覆盖面:
// │           XSS:toHtml 对 <script> 五字符转义(存储型 XSS 防护,Web 报表场景)
// │           标识符注入:表名/列名白名单拒绝(PreparedStatement 不支持标识符占位)
// │           URL scheme:readUrl 拒绝 file://(SSRF/本地文件读取面收窄)
// │           ThreadLocal:容器线程复用下引擎泄漏行为 + reset 恢复(行为锁,警示见 SqlEngines javadoc)
// │           参数化:Jian.query 用户输入走 Params 占位(非拼接)
class SecurityAuditTest {

    // XSS 防护:值含 <script> 的 DataFrame → toHtml 输出必须转义(否则 Web 展示=存储型 XSS)
    @Test
    void XSS_toHtml转义脚本注入() throws Exception {
        // 因为只验 < > 转义会漏掉 " ' & —— 属性注入上下文
        // (如 onmouseover="..." 的引号逃逸)不受 < > 转义约束,AGENTS.md §3.7.8 要求
        // toHtml 五字符全转义(< > & " ');生产 HtmlRenderer.escape 已实现,此处锁全。
        DataFrame df = DataFrame.of(Schema.of("用户输入", DType.STRING),
                new Object[][]{{"<script>alert('xss')</script>"},
                        {"<img src=x onerror=alert(1)>"},
                        {"a\"b & c'd"},
                        {"正常值"}});
        Path html = java.nio.file.Files.createTempFile("sec", ".html");
        Jian.toHtml(df, html.toString());
        String out = java.nio.file.Files.readString(html);
        assertThat(out).doesNotContain("<script>");          // 未转义的标签绝不能出现
        assertThat(out).doesNotContain("<img");              // 标签被转义成文本(onerror= 文本随之无害)
        assertThat(out).contains("&lt;script&gt;");          // 转义后的形态
        // 五字符转义:& " ' 全验(属性注入防线)
        assertThat(out).as("& 必须转义为 &amp;").contains("&amp;");
        assertThat(out).as("\" 必须转义为 &quot;").contains("&quot;");
        assertThat(out).as("' 必须转义为 &#39;").contains("&#39;");
        assertThat(out).as("组合载荷的原文绝不能出现").doesNotContain("a\"b & c'd");
        assertThat(out).as("组合载荷的转义形态").contains("a&quot;b &amp; c&#39;d");
        assertThat(out).contains("正常值");                    // 正常值不受影响
        java.nio.file.Files.deleteIfExists(html);
    }

    // 标识符注入防护:表名/列名进入 DDL/SELECT 拼接前必须过白名单(H2 内存库实测)
    @Test
    void SQL标识符注入被引号转义为字面量() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:sec_audit;DB_CLOSE_DELAY=-1", "sa", "")) {
            DataFrame df = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                    new Object[][]{{1L, "a"}});
            Jian.toSql(df, conn, "t");   // 正常表名先建好,供 DROP 验证目标存在
            // 表名注入:分号/引号/空格/-- 被库引号符包裹 + 双写转义 → 整串成为字面量怪名表,
            // 不产生任何 DROP/WHERE 语句;写入成功但目标表 t 安然无恙(注入挡在标识符层)
            for (String evil : new String[]{"t; DROP TABLE t--", "t WHERE 1=1", "t'; --"}) {
                Jian.toSql(df, conn, evil);
                assertThat(Jian.readSqlTable(conn, evil).rowCount()).as("怪名表 %s 字面量往返", evil).isEqualTo(1);
            }
            var rs = conn.createStatement().executeQuery("SELECT count(*) FROM t");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);   // 目标表未被破坏(仍有 1 行)
            // 控制字符表名硬拒绝(引号也救不了不可见字符)
            assertThatThrownBy(() -> Jian.toSql(df, conn, "t\t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("控制字符");
            // 列名注入:同口径被引号化为字面量列;a;b 成为一个真实存在的怪列名
            DataFrame bad = DataFrame.of(Schema.of("a;b", DType.LONG), new Object[][]{{1L}});
            Jian.toSql(bad, conn, "t2");
            assertThat(Jian.readSqlTable(conn, "t2").columnNames()).containsExactly("a;b");
        }
    }

    // URL scheme 收窄:readUrl 只许 http/https(file:// 等一律拒绝,防本地文件读取/协议滥用)
    @Test
    void readUrl拒绝非http协议() {
        assertThatThrownBy(() -> jian.io.html.Html.readUrl("file:///etc/passwd").go())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> jian.io.html.Html.readUrl("ftp://x/y").go())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ThreadLocal 泄漏:Tomcat 线程复用下 useCustom 未 reset → 同线程下一调用继承引擎(泄漏行为锁);
    // reset() 后恢复默认 —— 行为契约见 SqlEngines javadoc 的容器警示
    @Test
    void ThreadLocal引擎跨调用泄漏与reset修复() {
        SqlEngines.reset();
        try {
            assertThat(SqlEngines.current().name()).isEqualTo("regex");   // 默认引擎
            SqlEngines.useCustom(new jian.dsl.SqlEngineInterface() {
                @Override public String name() { return "evil-engine"; }
                @Override public DataFrame query(DataFrame df, String sql,
                        java.util.Map<String, DataFrame> bindings, jian.dsl.SqlDialect dialect) {
                    throw new IllegalStateException("被泄漏的引擎被执行了");
                }
            });
            // 模拟"同一线程的下一个请求"未 reset:引擎选择被继承(这正是容器风险,行为如实锁死)
            assertThat(SqlEngines.current().name()).isEqualTo("evil-engine");
        } finally {
            SqlEngines.reset();   // try-finally reset 是容器中的强制写法(javadoc 警示)
        }
        assertThat(SqlEngines.current().name()).isEqualTo("regex");       // reset 后恢复默认
    }

    // 参数化注入防护:用户输入一律 Params 占位(引擎字面量化 + '' 翻倍),不走字符串拼接
    @Test
    void 参数化查询注入尝试被字面量化() {
        DataFrame df = DataFrame.of(Schema.of("用户", DType.STRING),
                new Object[][]{{"alice"}, {"bob"}, {"O'Brien"}});
        String malicious = "x' || 'y";   // 尝试逃逸字符串字面量
        // 正确姿势:malicious 经 Params 注入,作为【整体字面量】比较 → 无匹配行,不报错不逃逸
        DataFrame r = Jian.query(df, "用户 == ${u}", Params.of("u", malicious));
        assertThat(r.rowCount()).isZero();
        DataFrame ok = Jian.query(df, "用户 == ${u}", Params.of("u", "O'Brien"));
        assertThat(ok.rowCount()).isEqualTo(1);   // 引号内容被 '' 翻倍正确转义,可命中
    }
}
