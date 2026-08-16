package jian.io.parquet;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ParquetRegressionTest —— Parquet 列存读写回归测试集
// │  Why  : 固化 Parquet 读写行为(因为 0 行 DataFrame 的列名与 dtype 必须按文件
// │         footer 元数据还原(否则读回 0 列)、非零行往返不回归等边界行为一旦退化
// │         会丢 schema,所以全部固化为本测试集;口径与 Json 的 0 行保列一致)。
// │  Who  : CI(./mvnw -Pcolumnar test -pl jian-io-parquet)
// │  When : 改动 Parquet.java 的空 records 分支 / emptyFromFooter 后必须跑
// │  Where: jian-io-parquet/src/test/java/jian/io/parquet/ParquetRegressionTest.java
// │  How  : 数据走向:0 行 DataFrame → Parquet.write → footer(schema)→ Parquet.read →
// │         断言列名与 dtype 都按写侧元数据还原。
class ParquetRegressionTest {

    @TempDir Path tmp;

    // ======================== 0 行保列(dtype 按元数据还原) ========================

    @Test
    void 零行DataFrame往返保留列名与dtype() throws Exception {
        // 因为 0 行时无数据可推断类型,若直接返回零列 DataFrame 会把 [id,name,vip,score]
        // 读成 [],所以 schema 落入 footer、读侧按 footer 元数据还原
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "vip", DType.BOOL, "score", DType.DOUBLE),
                new Object[0][4]);
        assertThat(df.rowCount()).isEqualTo(0);
        Path p = tmp.resolve("empty.parquet");
        Parquet.write(df, p.toString()).go();
        assertThat(java.nio.file.Files.size(p)).isGreaterThan(0);   // 文件非空(footer 有 schema)

        DataFrame r = Parquet.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.columnNames()).as("0 行不丢列").containsExactly("id", "name", "vip", "score");
        assertThat(r.dtypes()).as("0 行 dtype 按 footer 元数据还原")
                .containsExactly(DType.LONG, DType.STRING, DType.BOOL, DType.DOUBLE);
    }

    @Test
    void 零行INT列dtype同样保真() throws Exception {
        DataFrame df = DataFrame.of(Schema.of("n", DType.INT), new Object[0][1]);
        Path p = tmp.resolve("empty2.parquet");
        Parquet.write(df, p.toString()).go();
        DataFrame r = Parquet.read(p.toString()).go();
        assertThat(r.columnNames()).containsExactly("n");
        assertThat(r.dtypes()).containsExactly(DType.INT);
    }

    // ======================== 非零行:不回归 ========================

    @Test
    void 非零行往返不回归() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "vip", DType.BOOL),
                new Object[][]{{1L, true}, {2L, false}});
        Path p = tmp.resolve("basic.parquet");
        Parquet.write(df, p.toString()).go();
        DataFrame r = Parquet.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("id", "vip");
        assertThat(r.dtypes()).containsExactly(DType.LONG, DType.BOOL);
        assertThat(r.getColumn("vip").get(0)).isEqualTo(true);
    }
}
