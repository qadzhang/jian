package jian;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : FacadeRegressionTest —— jian-facade 回归测试集:固化 Jian 门面写出与源码生成行为
// │  Why  : 因为通用 write() 与显式 to* 必须同口径(判空、自动建父目录),
// │         generateColumnsSource 对 Java 关键字列名必须产出可编译源码,
// │         入口行为分裂或不可编译产出都会直接坑到使用者,所以用强断言锁住
// │  Who  : jian-facade 模块测试套件
// │  When : mvn test(jian-facade 模块)
// │  Where: jian-facade/src/test/java/jian/FacadeRegressionTest.java
// │  How  : ①write 到不存在的多级目录断言自动创建 + 文件可读回;write(null) 断言 IAE;
// │         ②关键字/字面量/保留字列名断言生成 `class_ = "class"`(可编译形态),
// │         非法列名仍走注释分支,className 为关键字时拒绝。
class FacadeRegressionTest {

    @TempDir Path tmp;

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
    }

    // ======================== write 判空 + 自动建父目录 ========================

    @Test
    void write自动创建不存在的父目录() throws Exception {
        // "不存在目录/out.csv" 须自动建目录(与显式 toCsv 同口径,不出现
        // 同一能力两条入口行为分裂)
        Path out = tmp.resolve("不存在的目录/深层/子目录/out.csv");
        Jian.write(df(), out.toString());
        assertThat(java.nio.file.Files.exists(out)).isTrue();
        // 写出的内容可读回(真实副作用,不是空文件)
        assertThat(Jian.read(out.toString()).rowCount()).isEqualTo(2);
    }

    @Test
    void write自动建父目录_json与html同样生效() throws Exception {
        Path json = tmp.resolve("a/b/c/out.json");
        Jian.write(df(), json.toString());
        assertThat(Jian.read(json.toString()).rowCount()).isEqualTo(2);
        Path html = tmp.resolve("x/y/z/out.html");
        Jian.write(df(), html.toString());
        assertThat(java.nio.file.Files.readString(html)).contains("<table");
    }

    @Test
    void write_null_df抛IAE() throws Exception {
        // write(null, ...) 必须入口判空抛 IAE(与显式 to* 同口径),不深层 NPE
        assertThatThrownBy(() -> Jian.write(null, tmp.resolve("out.csv").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("df 不能为 null");
    }

    @Test
    void 显式toCsv_多级目录自动创建保持不回归() throws Exception {
        Path out = tmp.resolve("explicit/dir/deep/out.csv");
        Jian.toCsv(df(), out.toString());
        assertThat(Jian.read(out.toString()).rowCount()).isEqualTo(2);
    }

    // ======================== generateColumnsSource 关键字列名 ========================

    @Test
    void 关键字列名加下划线后缀生成合法源码() {
        DataFrame df = DataFrame.of(
                Schema.of("class", DType.STRING, "int", DType.LONG, "ok", DType.STRING),
                new Object[][]{{"a", 1L, "b"}});
        String src = Jian.generateColumnsSource(df, "Cols");
        // 关键字列:常量名加 _ 后缀(裸 public static final String class = ... 不可编译)
        assertThat(src).contains("public static final String class_ = \"class\";");
        assertThat(src).contains("public static final String int_ = \"int\";");
        // 普通列不受影响
        assertThat(src).contains("public static final String ok = \"ok\";");
        // 不产出裸关键字常量名
        assertThat(src).doesNotContain("String class =");
        assertThat(src).doesNotContain("String int =");
    }

    @Test
    void 字面量与保留字列名同样加后缀() {
        DataFrame df = DataFrame.of(
                Schema.of("true", DType.BOOL, "null", DType.STRING, "new", DType.STRING, "goto", DType.STRING),
                new Object[][]{{true, "n", "x", "g"}});
        String src = Jian.generateColumnsSource(df, "Lit");
        assertThat(src).contains("public static final String true_ = \"true\";");
        assertThat(src).contains("public static final String null_ = \"null\";");
        assertThat(src).contains("public static final String new_ = \"new\";");
        assertThat(src).contains("public static final String goto_ = \"goto\";");
    }

    @Test
    void 非标识符列名仍走注释分支() {
        DataFrame df = DataFrame.of(
                Schema.of("bad col", DType.STRING, "class", DType.STRING),
                new Object[][]{{"a", "b"}});
        String src = Jian.generateColumnsSource(df, "Mix");
        assertThat(src).contains("无法常量化");   // 空格等非法字符:注释说明
        assertThat(src).contains("public static final String class_ = \"class\";");
    }

    @Test
    void className为关键字时拒绝() {
        // 类名同样不能是关键字(只查字符类会放行 class 这类不可编译类名)
        assertThatThrownBy(() -> Jian.generateColumnsSource(df(), "class"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合法 Java 标识符");
    }
}
