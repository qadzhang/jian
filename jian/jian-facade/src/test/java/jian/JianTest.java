package jian;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JianTest {

    @TempDir Path tmp;

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{{1L, "alice", 90.5}, {2L, "bob", 85.0}, {3L, "carol", 76.5}});
    }

    @Test
    void readCsv通用() throws Exception {
        Path p = tmp.resolve("d.csv");
        Jian.write(df(), p.toString());
        DataFrame r = Jian.read(p.toString());
        assertThat(r.rowCount()).isEqualTo(3);
    }

    @Test
    void read按扩展名分发() throws Exception {
        // csv/json/jpk 分发断言不依赖列存模块,不应被 parquet 的 skip 条件连坐
        // (否则默认构建下零覆盖)
        Path p1 = tmp.resolve("a.csv"); Jian.write(df(), p1.toString());
        Path p2 = tmp.resolve("b.json"); Jian.write(df(), p2.toString());
        Path p3 = tmp.resolve("c.jpk"); Jian.write(df(), p3.toString());

        assertThat(Jian.read(p1.toString()).rowCount()).isEqualTo(3);
        assertThat(Jian.read(p2.toString()).rowCount()).isEqualTo(3);
        assertThat(Jian.read(p3.toString()).rowCount()).isEqualTo(3);
    }

    @Test
    void readExcel通用() throws Exception {
        Path p = tmp.resolve("d.xlsx");
        Jian.write(df(), p.toString());
        DataFrame r = Jian.read(p.toString());
        assertThat(r.rowCount()).isEqualTo(3);
    }

    @Test
    void write按扩展名分发() throws Exception {
        // 非列存格式(csv/json/html/md/tex/jpk/xml/xlsx)的
        // 分发断言不锁在 columnarAvailable() 后(否则默认构建下
        // 通用 write 分发零覆盖);仅 parquet(需 jian-io-parquet)保留条件
        // 各种格式都能写出
        Jian.write(df(), tmp.resolve("a.csv").toString());
        Jian.write(df(), tmp.resolve("b.json").toString());
        Jian.write(df(), tmp.resolve("c.html").toString());
        Jian.write(df(), tmp.resolve("d.md").toString());
        Jian.write(df(), tmp.resolve("e.tex").toString());
        Jian.write(df(), tmp.resolve("f.jpk").toString());
        Jian.write(df(), tmp.resolve("g.xml").toString());
        Jian.write(df(), tmp.resolve("i.xlsx").toString());
        // 列存默认不编译:parquet 分支需 jian-io-parquet,未引 jar 时跳过(§0.2 优雅降级)
        java.util.List<String> checked = new java.util.ArrayList<>(
                java.util.Arrays.asList("a.csv", "b.json", "c.html", "d.md", "e.tex", "f.jpk", "g.xml", "i.xlsx"));
        if (columnarAvailable()) {
            Jian.write(df(), tmp.resolve("h.parquet").toString());
            checked.add("h.parquet");
        }
        // 文件都存在
        for (String f : checked) {
            assertThat(java.nio.file.Files.exists(tmp.resolve(f))).isTrue();
            assertThat(java.nio.file.Files.size(tmp.resolve(f))).isGreaterThan(0L);
        }
    }

    @Test
    void readSqlJDBC() throws Exception {
        String name = "facade_test_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE u (id BIGINT, name VARCHAR(100))");
            st.execute("INSERT INTO u VALUES (1,'alice'),(2,'bob')");
            DataFrame r = Jian.readSql(conn, "SELECT * FROM u WHERE id > ?", 0);
            assertThat(r.rowCount()).isEqualTo(2);
        }
    }

    @Test
    void sqlL3入口() {
        DataFrame r = Jian.sql("SELECT * FROM ${t} WHERE score > 80", df());
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void sql多表JOIN() {
        DataFrame users = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
        DataFrame ages = DataFrame.of(Schema.of("id", DType.LONG, "age", DType.LONG),
                new Object[][]{{1L, 30L}});
        DataFrame r = Jian.sql("SELECT * FROM ${users} JOIN ${ages} ON users.id = ages.id", users, ages);
        assertThat(r.rowCount()).isEqualTo(1);
    }

    @Test
    void 不支持的扩展名抛异常() {
        try {
            Jian.read("unknown.xyz");
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("不支持");
        }
    }

    @Test
    void toCsvBuilder() throws Exception {
        Path p = tmp.resolve("explicit.csv");
        Jian.toCsv(df(), p.toString());
        assertThat(Jian.read(p.toString()).rowCount()).isEqualTo(3);
    }

    @Test
    void version() {
        assertThat(Jian.version()).isNotEmpty();
    }

    @Test
    void pandas风格readWrite() throws Exception {
        // readCsv / toCsv
        Jian.toCsv(df(), tmp.resolve("pd.csv").toString());
        DataFrame r1 = Jian.readCsv(tmp.resolve("pd.csv").toString());
        assertThat(r1.rowCount()).isEqualTo(3);

        // readJson / toJson
        Jian.toJson(df(), tmp.resolve("pd.json").toString());
        DataFrame r2 = Jian.readJson(tmp.resolve("pd.json").toString());
        assertThat(r2.rowCount()).isEqualTo(3);

        // readExcel / toExcel
        Jian.toExcel(df(), tmp.resolve("pd.xlsx").toString());
        DataFrame r3 = Jian.readExcel(tmp.resolve("pd.xlsx").toString());
        assertThat(r3.rowCount()).isEqualTo(3);
    }

    // ======================== 门面补齐回归:tsv/orc/pickle 等 =========================

    @Test
    void tsv读写() throws Exception {
        // 因为 .tsv 按制表符分隔与 .csv 同族,所以 read()/write() 都必须有 tsv 分支,不可缺席
        Path p = tmp.resolve("d.tsv");
        Jian.write(df(), p.toString());
        DataFrame r = Jian.read(p.toString());
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void pickle往返() throws Exception {
        Path p = tmp.resolve("d.jpk");
        Jian.toPickle(df(), p.toString());
        DataFrame r = Jian.readPickle(p.toString());
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.getStringColumn("name").get(2)).isEqualTo("carol");
    }

    @Test
    void orc往返() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(columnarAvailable());
        Path p = tmp.resolve("d.orc");
        Jian.toOrc(df(), p.toString());
        DataFrame r = Jian.readOrc(p.toString());
        assertThat(r.rowCount()).isEqualTo(3);
    }

    @Test
    void jsonNormalize门面() throws Exception {
        String json = "{\"rows\":[{\"a\":1,\"o\":{\"x\":2}}]}";
        DataFrame r = Jian.jsonNormalize(json, "rows");
        assertThat(r.columnNames()).contains("a", "o.x");
        assertThat(r.getColumn("o.x").get(0)).isEqualTo(2);
    }

    @Test
    void readSqlQuery与toSql() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:jian_facade_sql;DB_CLOSE_DELAY=-1", "sa", "")) {
            DataFrame df = df();
            Jian.toSql(df, conn, "t");
            DataFrame r = Jian.readSqlQuery(conn, "SELECT name FROM t WHERE id > ?", 1);
            assertThat(r.rowCount()).isEqualTo(2);
            DataFrame all = Jian.readSqlTable(conn, "t");
            assertThat(all.rowCount()).isEqualTo(3);
        }
    }

    @Test
    void toMarkdown与toHtml() throws Exception {
        Path md = tmp.resolve("d.md");
        Jian.toMarkdown(df(), md.toString());
        assertThat(java.nio.file.Files.readString(md)).contains("|");
        Path html = tmp.resolve("d.html");
        Jian.toHtml(df(), html.toString());
        assertThat(java.nio.file.Files.readString(html)).contains("<table");
    }

    // ┌─ What : 端到端 —— 生成中文类别 Excel → readExcel → 按类别 Jian.sql 拆分 → toExcel
    // │  Why  : 回归"读 Excel 按中文类别列拆分"完整场景(中文列名 \w 不匹配 +
    // │         WHERE 单等号被拒会导致该最常见任务完全跑不通),锁住可用性
    // │  Who  : mvn -pl jian/jian-facade test
    // │  When : mvn test(jian-facade 模块),永久回归
    // │  Where: jian/jian-facade/src/test/java/jian/JianTest.java
    // │  How  : 数据走向:toExcel(中文列 df) → readExcel → 逐类别 Jian.sql(WHERE 类别 = 'x')
    // │         → 各自 toExcel → 再 readExcel 回读断言行数/内容。
    // │         逻辑路线:4 个类别各拆 1 个文件;任一步抛异常(行为回归)测试即红。
    @Test
    void 按中文类别拆分Excel端到端() throws Exception {
        DataFrame src = DataFrame.of(
                Schema.of("类别", DType.STRING, "名称", DType.STRING, "金额", DType.LONG),
                new Object[][]{
                        {"食品", "苹果", 10L}, {"文具", "铅笔", 5L},
                        {"食品", "面包", 8L}, {"饮料", "可乐", 6L},
                        {"家电", "风扇", 99L}, {"饮料", "雪碧", 6L}});
        Path srcPath = tmp.resolve("销售明细.xlsx");
        Jian.toExcel(src, srcPath.toString());
        DataFrame df = Jian.readExcel(srcPath.toString());

        java.util.List<String> categories = new java.util.ArrayList<>();
        for (Object v : Jian.sql("SELECT DISTINCT 类别 FROM ${t}", df).getColumn("类别").toObjectArray()) {
            categories.add((String) v);
        }
        assertThat(categories).containsExactlyInAnyOrder("食品", "文具", "饮料", "家电");

        for (String cat : categories) {
            // 注入防护:用户输入(类别值)经 Params 占位注入,不走 SQL 字符串拼接
            DataFrame part = Jian.query(df, "类别 == ${c}", jian.dsl.Params.of("c", cat));
            Path out = tmp.resolve(cat + ".xlsx");
            Jian.toExcel(part, out.toString());
            // 回读验证:文件存在、行数正确、列名保持中文
            DataFrame back = Jian.readExcel(out.toString());
            assertThat(back.columnNames()).containsExactly("类别", "名称", "金额");
            java.util.List<Object> backCats = java.util.Arrays.asList(back.getColumn("类别").toObjectArray());
            assertThat(backCats).allMatch(cat::equals);
        }
        assertThat(tmp.resolve("食品.xlsx").toFile()).exists();
        assertThat(Jian.readExcel(tmp.resolve("食品.xlsx").toString()).rowCount()).isEqualTo(2);
    }

    // ┌─ What : 列名常量类生成(借鉴 Kotlin DataFrame schema 常量化,消灭列名拼写错)
    // │  How  : 断言合法列名(含中文)生成常量、非法列名(含空格)以注释说明;源码仅返回不落盘
    @Test
    void generateColumnsSource生成常量类() {
        DataFrame df = DataFrame.of(
                Schema.of("类别", DType.STRING, "金额", DType.LONG, "bad col", DType.STRING),
                new Object[][]{{"a", 1L, "x"}});
        String src = Jian.generateColumnsSource(df, "OrderCols");
        assertThat(src).contains("public final class OrderCols");
        assertThat(src).contains("public static final String 类别 = \"类别\";");
        assertThat(src).contains("public static final String 金额 = \"金额\";");
        assertThat(src).contains("bad col").contains("无法常量化");   // 非法标识符注释说明
        assertThatThrownBy(() -> Jian.generateColumnsSource(df, "1bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合法 Java 标识符");
    }

    /** 列存引擎类是否在 classpath(parquet/orc 任一;默认构建 false → 相关测试 skip)。 */
    private static boolean columnarAvailable() {
        try { Class.forName("jian.io.parquet.Parquet"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}
