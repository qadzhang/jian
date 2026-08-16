package jian.io.xml;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : XmlRegressionTest —— XML 读写回归测试集
// │  Why  : 固化 XML 读写行为(因为空元素缺失语义、0 行保列(cols 属性)、含引号/
// │         & / < / 逗号 / 百分号等元字符列名的往返还原等边界行为一旦回归会产出
// │         非法 XML 或丢列名,所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-xml)
// │  When : 改动 Xml 的空元素处理 / 0 行 cols 属性写出读回后必须跑
// │  Where: jian-io-xml/src/test/java/jian/io/xml/XmlRegressionTest.java
// │  How  : 数据走向:手造 DataFrame / XML 字符串 → Xml.write/parse/read →
// │         断言列名、行数、值与 dtype;元字符列名断言逐字符还原(写出/读回互逆)。
class XmlRegressionTest {

    @TempDir Path tmp;

    // ======================== 空元素:缺失语义 ========================

    @Test
    void 空元素读回为null且不污染dtype() throws Exception {
        // 因为空元素 <id></id> 表示缺失,与 CSV 的空字段语义对称,
        // 所以读回 null 且不参与 dtype 推断(不把整列拉成 STRING)
        DataFrame df = Xml.parse(
                "<rows><row><id>5</id><v>1.5</v></row><row><id></id><v>2.5</v></row></rows>", "row");
        assertThat(df.getColumn("id").dtype()).as("空元素不污染推断").isEqualTo(DType.INT);
        assertThat(df.getColumn("id").get(0)).isEqualTo(5);
        assertThat(df.getColumn("id").isNull(1)).as("空元素=缺失").isTrue();
    }

    // ======================== 0 行:cols 属性保列 ========================

    @Test
    void 零行XML往返保留列名() throws Exception {
        // 因为 0 行表没有数据行可推列名,所以根元素带 cols="a,b" 属性承载列名,
        // 读侧按属性还原空表
        DataFrame empty = DataFrame.of(Schema.of("a", DType.LONG, "b", DType.STRING), new Object[0][]);
        Path p = tmp.resolve("e.xml");
        Xml.write(empty, p.toString()).go();
        assertThat(java.nio.file.Files.readString(p)).contains("cols=\"a,b\"");
        DataFrame back = Xml.parse(java.nio.file.Files.readString(p), "row");
        assertThat(back.columnNames()).containsExactly("a", "b");
        assertThat(back.rowCount()).isZero();
    }

    // ======================== 0 行元字符列名:合法 XML + 无歧义还原 ========================

    @Test
    void 零行列名含引号与符号往返还原() throws Exception {
        // 因为 cols 属性裸拼列名时,"a"b,c&d" 会产出非法 XML(解析直接失败),
        // 所以列名经属性转义写出,读侧逐字符还原
        DataFrame df = DataFrame.of(
                Schema.of("a\"b", DType.STRING, "c&d", DType.STRING, "e<f", DType.STRING),
                new Object[0][3]);
        Path p = tmp.resolve("meta.xml");
        Xml.write(df, p.toString()).go();

        // 文件本体可解析(合法 XML)
        DataFrame r = Xml.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.columnNames()).as("元字符列名逐字符还原").containsExactly("a\"b", "c&d", "e<f");
    }

    @Test
    void 零行列名含逗号往返还原() throws Exception {
        // 因为列名分隔符就是逗号,&#44; 形式的实体又会被 XML 解析器解码回 ','
        // (split 后名字被切断),所以逗号用 %2C 百分号编码(解析器不动字面文本),
        // 读侧对称还原
        DataFrame df = DataFrame.of(
                Schema.of("x,y", DType.STRING, "normal", DType.STRING),
                new Object[0][2]);
        Path p = tmp.resolve("comma.xml");
        Xml.write(df, p.toString()).go();
        DataFrame r = Xml.read(p.toString()).go();
        assertThat(r.columnNames()).containsExactly("x,y", "normal");
    }

    @Test
    void 零行列名含百分号编码不歧义() throws Exception {
        // 列名自含 %2C / %25 字面量时,编码/解码顺序(%先转义、%2C 先还原)保证无歧义还原。
        DataFrame df = DataFrame.of(
                Schema.of("a%2C", DType.STRING, "b%25", DType.STRING),
                new Object[0][2]);
        Path p = tmp.resolve("pct.xml");
        Xml.write(df, p.toString()).go();
        DataFrame r = Xml.read(p.toString()).go();
        assertThat(r.columnNames()).containsExactly("a%2C", "b%25");
    }

    // ======================== 不回归:普通列名 / 非零行 ========================

    @Test
    void 普通零行列名行为不回归() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[0][2]);
        Path p = tmp.resolve("plain.xml");
        Xml.write(df, p.toString()).go();
        DataFrame r = Xml.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.columnNames()).containsExactly("id", "name");
    }

    @Test
    void 非零行元字符列名写出读回不回归() throws Exception {
        // 非 0 行路径(escapeName 清洗 + escape 文本转义)不受 cols 属性编码影响,锁定不回归。
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING),
                new Object[][]{{"a<b"}, {"c&d"}});
        Path p = tmp.resolve("rows.xml");
        Xml.write(df, p.toString()).go();
        DataFrame r = Xml.read(p.toString()).go();
        assertThat(r.columnNames()).containsExactly("name");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("a<b");
        assertThat(r.getStringColumn("name").get(1)).isEqualTo("c&d");
    }
}
