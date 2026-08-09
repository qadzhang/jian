package jian.io.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jian.core.Column;
import jian.core.DataFrame;
import jian.core.Schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ┌─ What : Json —— JSON 读写(对齐 pandas read_json / to_json,基于 jackson)
// │  Why  : 规范 02 §3.3;JSON 5 种 orient(records/columns/values/index/split)是 pandas 数据交换核心
// │  Who  : 用户经 Jian.readJson 入口,或直接 Json.read/write
// │  When : API 响应、配置文件、跨语言数据交换
// │  Where: jian-io-json/Json.java
// │  How  : 数据走向:
// │           读:Path/字符串 → jackson ObjectMapper → JsonNode → 按 orient 解析 → DataFrame;
// │           写:DataFrame → 按 orient 构造 ObjectNode/ArrayNode → JSON 字符串/文件。
// │         关键变量变化:
// │           - orient:决定 JSON 结构(records=[{col:val}]/columns={col:[vals]}/...);
// │           - 日期:默认 ISO-8601 字符串(M3 不做复杂日期解析)。
// │         逻辑路线:
// │           路径 A(读 records)→ array of object,每个 object 一行;
// │           路径 B(读 columns)→ object of array,每个 key 是列;
// │           路径 C(读 values)→ array of array,纯值(取首行作列名 or _0/_1);
// │           路径 D(读 index/split)→ split 含 columns+index+data 三段。
/**
 * JSON 读写,对齐 pandas.read_json / to_json。
 *
 * <p>支持 pandas 全部 5 种 orient:
 * <ul>
 *   <li>{@code RECORDS}(默认):{@code [{"a":1,"b":2},...]} —— 最常用,适合行列表;</li>
 *   <li>{@code COLUMNS}:{@code {"a":[1,...],"b":[...]}} —— 列存;</li>
 *   <li>{@code VALUES}:{@code [[1,2],[3,4]]} —— 纯二维数组,首行可作列名;</li>
 *   <li>{@code INDEX}:{@code {"a":{"0":1,"1":3},"b":{...}}} —— 带行索引的列存;</li>
 *   <li>{@code SPLIT}:{@code {"columns":["a","b"],"index":[...],"data":[[1,2]]}} —— 完整 schema + 数据。</li>
 * </ul>
 */
public final class Json {

    private Json() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Orient { RECORDS, COLUMNS, VALUES, INDEX, SPLIT }

    // ======================== 读 ========================

    /**
     * 读 JSON 的 builder(默认 RECORDS orient)。
     * @param path String JSON 文件路径,需为合法可读文件,不允许 null
     * @return JsonReader 配置器,链式调用 .orient 后 .go() 执行
     */
    public static JsonReader read(String path) { return new JsonReader(Path.of(path)); }

    public static final class JsonReader {
        private final Path path;
        private Orient orient = Orient.RECORDS;

        JsonReader(Path p) { this.path = p; }

        /**
         * 设置 JSON 结构 orient。
         * @param o Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT,默认 RECORDS
         * @return JsonReader 当前配置器,便于链式调用
         */
        public JsonReader orient(Orient o) { this.orient = o; return this; }

        public DataFrame go() throws IOException {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parse(content, orient);
        }
    }

    /**
     * 从字符串解析(供 Jian.jsonNormalize 等复用)。
     * @param json String JSON 文本内容,需符合所选 orient 的结构,不允许 null
     * @param orient Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT
     * @return DataFrame 解析出的数据帧(列名与类型按 orient 语义和值推断)
     * @throws IOException JSON 格式错误或与 orient 不匹配时抛出
     */
    public static DataFrame parse(String json, Orient orient) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        switch (orient) {
            case RECORDS: return parseRecords(root);
            case COLUMNS: return parseColumns(root);
            case VALUES: return parseValues(root);
            case INDEX: return parseIndex(root);
            case SPLIT: return parseSplit(root);
            default: throw new IllegalArgumentException("未知 orient: " + orient);
        }
    }

    /** records:[{col:val},...]。 */
    private static DataFrame parseRecords(JsonNode root) {
        if (!root.isArray()) throw new IllegalArgumentException("RECORDS orient 要求顶层数组");
        ArrayNode arr = (ArrayNode) root;
        if (arr.isEmpty()) return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        // 取列名(首元素的 keys,保序)
        List<String> names = new ArrayList<>();
        Iterator<String> keys = arr.get(0).fieldNames();
        while (keys.hasNext()) names.add(keys.next());
        Object[][] rows = new Object[arr.size()][names.size()];
        for (int r = 0; r < arr.size(); r++) {
            JsonNode obj = arr.get(r);
            for (int c = 0; c < names.size(); c++) rows[r][c] = nodeToValue(obj.get(names.get(c)));
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /** columns:{col:[vals],...}。 */
    private static DataFrame parseColumns(JsonNode root) {
        if (!root.isObject()) throw new IllegalArgumentException("COLUMNS orient 要求顶层对象");
        Map<String, Object[]> cols = new LinkedHashMap<>();
        int n = -1;
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            JsonNode arr = root.get(name);
            if (!arr.isArray()) throw new IllegalArgumentException("COLUMNS orient 每个值须数组");
            Object[] vals = new Object[arr.size()];
            for (int i = 0; i < arr.size(); i++) vals[i] = nodeToValue(arr.get(i));
            cols.put(name, vals);
            if (n < 0) n = vals.length;
        }
        return DataFrame.ofColumns(cols);
    }

    /** values:[[1,2],[3,4]]。 */
    private static DataFrame parseValues(JsonNode root) {
        if (!root.isArray()) throw new IllegalArgumentException("VALUES orient 要求顶层数组");
        ArrayNode arr = (ArrayNode) root;
        if (arr.isEmpty()) return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        int cols = arr.get(0).size();
        List<String> names = new ArrayList<>();
        for (int c = 0; c < cols; c++) names.add("_" + c);
        Object[][] rows = new Object[arr.size()][cols];
        for (int r = 0; r < arr.size(); r++) {
            JsonNode row = arr.get(r);
            for (int c = 0; c < cols; c++) rows[r][c] = nodeToValue(row.get(c));
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /** index:{"col":{"0":val,"1":val},...} —— 带 index 的列存,index 忽略(用位置)。 */
    private static DataFrame parseIndex(JsonNode root) {
        if (!root.isObject()) throw new IllegalArgumentException("INDEX orient 要求顶层对象");
        Map<String, Object[]> cols = new LinkedHashMap<>();
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            JsonNode obj = root.get(name);
            if (!obj.isObject()) throw new IllegalArgumentException("INDEX orient 每列须是 {idx:val}");
            // 收集 values(按 key 排序作为行序;key 是行索引字符串)
            List<String> idxKeys = new ArrayList<>();
            Iterator<String> kit = obj.fieldNames();
            while (kit.hasNext()) idxKeys.add(kit.next());
            idxKeys.sort(String::compareTo);
            Object[] vals = new Object[idxKeys.size()];
            for (int i = 0; i < idxKeys.size(); i++) vals[i] = nodeToValue(obj.get(idxKeys.get(i)));
            cols.put(name, vals);
        }
        return DataFrame.ofColumns(cols);
    }

    /** split:{"columns":[...],"index":[...],"data":[[...],...]}。 */
    private static DataFrame parseSplit(JsonNode root) {
        if (!root.isObject()) throw new IllegalArgumentException("SPLIT orient 要求顶层对象");
        JsonNode colsNode = root.get("columns");
        JsonNode dataNode = root.get("data");
        if (colsNode == null || dataNode == null) {
            throw new IllegalArgumentException("SPLIT orient 须含 columns 和 data");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode n : colsNode) names.add(n.asText());
        Object[][] rows = new Object[dataNode.size()][names.size()];
        for (int r = 0; r < dataNode.size(); r++) {
            JsonNode row = dataNode.get(r);
            for (int c = 0; c < names.size(); c++) rows[r][c] = nodeToValue(row.get(c));
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /** JsonNode → Java 值。 */
    private static Object nodeToValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isTextual()) return node.textValue();
        // 复杂类型(对象/数组)保留为 toString(M3 简化;v2 用 OBJECT 列存 JsonNode)
        return node.toString();
    }

    // ======================== json_normalize(拍平嵌套 JSON,对齐 pandas.json_normalize)========================

    /**
     * 拍平嵌套 JSON(对齐 pandas.json_normalize,规范 02 §2.1/§3.3)。
     *
     * <p>按点号路径(如 {@code "results.items"})定位到对象数组,把每个元素的嵌套对象
     * 拍平为"点号分隔"的列;嵌套对象数组按下标展开({@code items.0.name})。
     * <pre>{@code
     * DataFrame df = Json.normalize("{\"results\":{\"items\":[{\"a\":1,\"o\":{\"x\":2}}]}}", "results.items");
     * // 列:a, o.x
     * }</pre>
     *
     * @param json JSON 字符串
     * @param recordPath 点号路径(逐层取对象字段,最后必须落到数组)
     */
    public static DataFrame normalize(String json, String recordPath) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        // 数据走向:root → 按点号路径逐层 get → 数组 arr → 逐元素拍平 → Map 列表 → Object[][] → DataFrame
        // recordPath 为 "$" 或空时,输入本身就是数组(pandas normalize 默认语义)
        JsonNode arr = (recordPath == null || recordPath.isBlank() || recordPath.equals("$")) ? root : root;
        if (!recordPath.isBlank() && !recordPath.equals("$")) {
            for (String part : recordPath.split("\\.")) {
                if (arr == null) break;
                arr = arr.get(part);
            }
        }
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("jsonNormalize:路径 '" + recordPath + "' 未找到数组");
        }
        // 第一遍:拍平所有元素,收集全量键集合(union,保证列一致)
        List<Map<String, Object>> flat = new ArrayList<>();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (JsonNode el : arr) {
            if (!el.isObject()) {
                throw new IllegalArgumentException("jsonNormalize:数组元素须为对象,实际 " + el.getNodeType());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            flattenNode(el, "", m, keys);
            flat.add(m);
        }
        // 第二遍:按 keys 顺序填表(缺失的键补 null)
        List<String> names = new ArrayList<>(keys);
        Object[][] data = new Object[flat.size()][names.size()];
        for (int r = 0; r < flat.size(); r++) {
            Map<String, Object> m = flat.get(r);
            for (int c = 0; c < names.size(); c++) data[r][c] = m.get(names.get(c));
        }
        return DataFrame.of(Schema.infer(names, data), data);
    }

    /** 递归拍平:对象 → 点号前缀递归;标量数组 → 原样存 List;对象数组 → 下标展开。 */
    private static void flattenNode(JsonNode node, String prefix, Map<String, Object> out,
                                    java.util.Set<String> keys) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    flattenNode(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(),
                            out, keys));
        } else if (node.isArray()) {
            boolean allObj = !node.isEmpty() && node.get(0).isObject();
            if (allObj) {
                for (int i = 0; i < node.size(); i++) {
                    flattenNode(node.get(i), prefix + "." + i, out, keys);
                }
            } else {
                // 标量数组:整列存 List(Object dtype)
                List<Object> vals = new ArrayList<>();
                node.forEach(n -> vals.add(nodeToValue(n)));
                putFlat(prefix, vals, out, keys);
            }
        } else {
            putFlat(prefix, nodeToValue(node), out, keys);
        }
    }

    private static void putFlat(String key, Object val, Map<String, Object> out, java.util.Set<String> keys) {
        out.put(key, val);
        keys.add(key);
    }

    // ======================== 写 ========================

    /**
     * 写 JSON 的 builder(默认 RECORDS orient)。
     * @param df DataFrame 要写出的数据帧,不允许 null
     * @param path String 输出 JSON 文件路径,需为合法可写路径,不允许 null
     * @return JsonWriter 配置器,链式调用 .orient 后 .go() 执行
     */
    public static JsonWriter write(DataFrame df, String path) { return new JsonWriter(df, Path.of(path)); }

    public static final class JsonWriter {
        private final DataFrame df;
        private final Path path;
        private Orient orient = Orient.RECORDS;

        JsonWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        /**
         * 设置 JSON 结构 orient。
         * @param o Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT,默认 RECORDS
         * @return JsonWriter 当前配置器,便于链式调用
         */
        public JsonWriter orient(Orient o) { this.orient = o; return this; }

        public void go() throws IOException {
            String json = toJsonString(df, orient);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        }
    }

    /**
     * DataFrame → JSON 字符串(指定 orient)。
     * @param df DataFrame 要序列化的数据帧,不允许 null
     * @param orient Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT
     * @return String 指定 orient 的 JSON 文本(UTF-8 语义);NaN/Infinity 按 null 输出以兼容标准 JSON
     * @throws IOException 序列化过程发生 IO 错误时抛出
     */
    public static String toJsonString(DataFrame df, Orient orient) throws IOException {
        switch (orient) {
            case RECORDS: return writeRecords(df);
            case COLUMNS: return writeColumns(df);
            case VALUES: return writeValues(df);
            case SPLIT: return writeSplit(df);
            case INDEX: return writeIndex(df);
            default: throw new IllegalArgumentException("未知 orient: " + orient);
        }
    }

    private static String writeRecords(DataFrame df) throws IOException {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Object[] row : df.iterRows()) {
            ObjectNode obj = arr.addObject();
            List<String> names = df.columnNames();
            for (int c = 0; c < names.size(); c++) putValue(obj, names.get(c), row[c]);
        }
        return MAPPER.writeValueAsString(arr);
    }

    private static String writeColumns(DataFrame df) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        List<String> names = df.columnNames();
        for (String name : names) {
            ArrayNode arr = root.putArray(name);
            Column col = df.getColumn(name);
            for (int r = 0; r < df.rowCount(); r++) addValue(arr, col.get(r));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String writeValues(DataFrame df) throws IOException {
        ArrayNode arr = MAPPER.createArrayNode();
        List<String> names = df.columnNames();
        for (Object[] row : df.iterRows()) {
            ArrayNode inner = arr.addArray();
            for (int c = 0; c < names.size(); c++) addValue(inner, row[c]);
        }
        return MAPPER.writeValueAsString(arr);
    }

    private static String writeSplit(DataFrame df) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode cols = root.putArray("columns");
        for (String n : df.columnNames()) cols.add(n);
        ArrayNode idx = root.putArray("index");
        for (int r = 0; r < df.rowCount(); r++) idx.add(r);
        ArrayNode data = root.putArray("data");
        List<String> names = df.columnNames();
        for (Object[] row : df.iterRows()) {
            ArrayNode inner = data.addArray();
            for (int c = 0; c < names.size(); c++) addValue(inner, row[c]);
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String writeIndex(DataFrame df) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        List<String> names = df.columnNames();
        for (String name : names) {
            ObjectNode col = root.putObject(name);
            Column c = df.getColumn(name);
            for (int r = 0; r < df.rowCount(); r++) putValue(col, String.valueOf(r), c.get(r));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static void putValue(ObjectNode obj, String key, Object v) {
        if (v == null) obj.putNull(key);
        else if (v instanceof Integer) obj.put(key, (Integer) v);
        else if (v instanceof Long) obj.put(key, (Long) v);
        else if (v instanceof Double d) {
            // 修复(AI agent1 / AI agent2 双 AI 复审):NaN/Infinity 在标准 JSON 不允许,
            // Jackson 默认会抛异常或写成非数字 token 导致读回类型损坏(如 Infinity 变字符串)。
            // 统一按"缺失"语义输出 null(与 jian-core 的 DataFrame 缺失处理一致)。
            if (Double.isNaN(d) || Double.isInfinite(d)) obj.putNull(key);
            else obj.put(key, d);
        }
        else if (v instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) obj.putNull(key);
            else obj.put(key, f);
        }
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) obj.putNull(key);
            else obj.put(key, d);
        }
        else if (v instanceof Boolean) obj.put(key, (Boolean) v);
        else obj.put(key, String.valueOf(v));
    }

    private static void addValue(ArrayNode arr, Object v) {
        if (v == null) arr.addNull();
        else if (v instanceof Integer) arr.add((Integer) v);
        else if (v instanceof Long) arr.add((Long) v);
        else if (v instanceof Double d) {
            // 同 putValue:NaN/Infinity 转 null(标准 JSON 兼容)
            if (Double.isNaN(d) || Double.isInfinite(d)) arr.addNull();
            else arr.add(d);
        }
        else if (v instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) arr.addNull();
            else arr.add(f);
        }
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) arr.addNull();
            else arr.add(d);
        }
        else if (v instanceof Boolean) arr.add((Boolean) v);
        else arr.add(String.valueOf(v));
    }
}
