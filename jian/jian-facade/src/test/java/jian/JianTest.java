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
        // 各种格式都能写出
        Jian.write(df(), tmp.resolve("a.csv").toString());
        Jian.write(df(), tmp.resolve("b.json").toString());
        Jian.write(df(), tmp.resolve("c.html").toString());
        Jian.write(df(), tmp.resolve("d.md").toString());
        Jian.write(df(), tmp.resolve("e.tex").toString());
        Jian.write(df(), tmp.resolve("f.jpk").toString());
        Jian.write(df(), tmp.resolve("g.xml").toString());
        Jian.write(df(), tmp.resolve("h.parquet").toString());
        Jian.write(df(), tmp.resolve("i.xlsx").toString());
        // 文件都存在
        for (String f : new String[]{"a.csv","b.json","c.html","d.md","e.tex","f.jpk","g.xml","h.parquet","i.xlsx"}) {
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

    // ======================== 2026-08-02 门面补齐回归:tsv/orc/pickle 等 ========================

    @Test
    void tsv读写() throws Exception {
        // 修复前:.tsv 在 read()/write() 无分支,直接抛"不支持"(但错误信息声称支持)
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
}

