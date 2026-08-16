package jian.io.xml;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class XmlTest {

    @TempDir Path tmp;

    @Test
    void 读写往返() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
        Path p = tmp.resolve("data.xml");
        Xml.write(df, p.toString()).go();

        DataFrame r = Xml.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("id", "name");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void 自定义rowName() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.STRING),
                new Object[][]{{"x"}, {"y"}});
        Path p = tmp.resolve("data.xml");
        Xml.write(df, p.toString()).rowName("item").go();

        DataFrame r = Xml.read(p.toString()).rowName("item").go();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getStringColumn("v").get(0)).isEqualTo("x");
    }

    @Test
    void 从字符串解析() throws Exception {
        String xml = "<rows><row><id>1</id><name>alice</name></row>"
                + "<row><id>2</id><name>bob</name></row></rows>";
        DataFrame r = Xml.parse(xml, "row");
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("id", "name");
    }

    @Test
    void 含中文() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("姓名", DType.STRING),
                new Object[][]{{"张三"}, {"李四"}});
        Path p = tmp.resolve("zh.xml");
        Xml.write(df, p.toString()).go();
        DataFrame r = Xml.read(p.toString()).go();
        assertThat(r.getStringColumn("姓名").get(0)).isEqualTo("张三");
    }

    // ======================== 安全:非法字符写出(标识符合法化 + 值转义) ========================

    @Test
    void 非法列名与值写出() throws Exception {
        // 列名含空格/&(XML 名称非法字符)→ 元素名替换为 _ 保证合法;根元素 cols 属性
        // 携带原始列名(读侧据此还原,往返不再改名);
        // 值含 & < > → 转义后读回原文
        DataFrame df = DataFrame.of(
                Schema.of("a b", DType.STRING, "c&d", DType.STRING),
                new Object[][]{{"x&y", "p<q>r"}});
        Path p = tmp.resolve("special.xml");
        Xml.write(df, p.toString()).go();
        String content = java.nio.file.Files.readString(p);
        assertThat(content).contains("<a_b>");              // 非法名清洗为合法元素名
        assertThat(content).contains("cols=");              // 原始列名进 cols 元数据属性
        DataFrame r = Xml.read(p.toString()).go();
        // 读回还原原始列名(不再是有损的 a_b/c_d)
        assertThat(r.columnNames()).containsExactly("a b", "c&d");
        assertThat(r.getStringColumn("c&d").get(0)).isEqualTo("p<q>r");
        assertThat(r.getStringColumn("a b").get(0)).isEqualTo("x&y");
    }
}

