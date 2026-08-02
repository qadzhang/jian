package jian.io.json;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import jian.io.json.Json.Orient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : jian-io-json 测试 —— 5 种 orient 读写往返
class JsonTest {

    @TempDir Path tmp;

    @Test
    void records往返() throws Exception {
        DataFrame df = df();
        String json = Json.toJsonString(df, Orient.RECORDS);
        DataFrame r = Json.parse(json, Orient.RECORDS);
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void columns往返() throws Exception {
        DataFrame df = df();
        String json = Json.toJsonString(df, Orient.COLUMNS);
        DataFrame r = Json.parse(json, Orient.COLUMNS);
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score");
        assertThat(r.getStringColumn("name").get(1)).isEqualTo("bob");
    }

    @Test
    void values往返_列名用下划线() throws Exception {
        DataFrame df = df();
        String json = Json.toJsonString(df, Orient.VALUES);
        DataFrame r = Json.parse(json, Orient.VALUES);
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("_0", "_1", "_2");
    }

    @Test
    void split往返() throws Exception {
        DataFrame df = df();
        String json = Json.toJsonString(df, Orient.SPLIT);
        DataFrame r = Json.parse(json, Orient.SPLIT);
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score");
    }

    @Test
    void index往返() throws Exception {
        DataFrame df = df();
        String json = Json.toJsonString(df, Orient.INDEX);
        DataFrame r = Json.parse(json, Orient.INDEX);
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score");
    }

    @Test
    void 文件读写往返() throws Exception {
        Path p = tmp.resolve("data.json");
        DataFrame df = df();
        Json.write(df, p.toString()).orient(Orient.RECORDS).go();

        DataFrame r = Json.read(p.toString()).orient(Orient.RECORDS).go();
        assertThat(r.rowCount()).isEqualTo(3);
    }

    @Test
    void records含中文和缺失() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("姓名", DType.STRING, "年龄", DType.LONG),
                new Object[][]{{"张三", 30L}, {"李四", null}});
        String json = Json.toJsonString(df, Orient.RECORDS);
        DataFrame r = Json.parse(json, Orient.RECORDS);
        assertThat(r.getStringColumn("姓名").get(0)).isEqualTo("张三");
        // 年龄含 null,JSON 读回推断为数值列;用 getColumn 取值更稳
        assertThat(r.getColumn("年龄").get(1)).isNull();
    }

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{
                        {1L, "alice", 90.5},
                        {2L, "bob", 85.0},
                        {3L, "carol", 76.5}
                });
    }

    // ======================== 2026-08-02 新增:json_normalize(规范 02 §2.1/§3.3) ========================

    @Test
    void normalize拍平嵌套() throws Exception {
        String json = "{\"results\":{\"items\":["
                + "{\"a\":1,\"o\":{\"x\":2,\"y\":3}},"
                + "{\"a\":4,\"o\":{\"x\":5}}]}}";
        DataFrame r = Json.normalize(json, "results.items");
        assertThat(r.columnNames()).containsExactly("a", "o.x", "o.y");
        assertThat(r.getColumn("a").get(0)).isEqualTo(1);
        assertThat(r.getColumn("o.x").get(0)).isEqualTo(2);
        assertThat(r.getColumn("o.y").get(1)).isNull();  // 第二行缺 o.y → null
    }

    @Test
    void normalize对象数组展开() throws Exception {
        String json = "[{\"items\":[{\"n\":1},{\"n\":2}],\"id\":\"x\"}]";
        DataFrame r = Json.normalize(json, "$");
        assertThat(r.columnNames()).contains("id", "items.0.n", "items.1.n");
        assertThat(r.getColumn("items.1.n").get(0)).isEqualTo(2);
    }

    @Test
    void normalize路径不存在报错() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                Json.normalize("{\"a\":1}", "no.such.path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到数组");
    }
}

