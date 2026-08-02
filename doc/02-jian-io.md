# 02 · jian-io 需求说明书

> 版本:v0.2(大面对齐 pandas)· 日期:2026-08-01 · 作者:zc · 依赖:jian-core + 各数据源解析/驱动库(按需)

---

## 1. 模块定位

### 1.1 一句话定位

jian-io 负责 **DataFrame 与外部数据源之间的双向转换**,**大面对齐 pandas 3.x 的 IO 接口矩阵**。覆盖 pandas 全部主流格式 + 7 种数据库,冷门统计软件格式(Stata/SAS/SPSS/GBQ/Iceberg/HDF5/Feather)**留接口、不实现**。

### 1.2 支持的数据源全景(对齐 pandas IO 参考)

#### Tier 1 —— 全实现(pandas 主流格式 + 全部要求的数据源)

| # | pandas 对应 | 类型 | 读 | 写 | 依赖 jar(按需引) | 子模块 |
|---|---|---|---|---|---|---|
| 1 | `read_csv` / `read_table` / `read_fwf` / `to_csv` | 文本 | ✅ | ✅ | `commons-csv` | jian-io-csv |
| 2 | `read_excel` / `to_excel` / `ExcelWriter` | Excel | ✅ | ✅ | `poi-ooxml`(非 uber,含 xls/xlsx;传递依赖由 Maven 拉) | jian-io-excel |
| 3 | `read_json` / `to_json` / `json_normalize` | JSON | ✅ | ✅ | `jackson-databind` | jian-io-json |
| 4 | `read_html` / `to_html` | HTML 表格 | ✅ | ✅ | 读:`jsoup` 1.18.3 / 写:纯 JDK | jian-io-html |
| 5 | `read_xml` / `to_xml` | XML | ✅ | ✅ | JDK 内置 `javax.xml` + Jackson XML | jian-io-xml |
| 6 | `read_sql` / `read_sql_table` / `read_sql_query` / `to_sql` | SQL | ✅ | ✅ | 各数据库 JDBC 驱动(见下) | jian-io-sql |
| 7 | `read_parquet` / `to_parquet` | 列式二进制 | ✅ | ✅ | `parquet-avro` / `parquet-hadoop`(可去 Hadoop,见 §3.7) | jian-io-parquet |
| 8 | `read_orc` / `to_orc` | 列式二进制 | ✅ | ✅ | `orc-core` / `orc-tools` | jian-io-orc |
| 9 | `read_pickle` / `to_pickle` | 二进制序列化 | ✅ | ✅ | **自写 jian-io-pickle 格式**(不依赖 Kryo/JDK 序列化,见 §3.9) | jian-io-pickle |
| 10 | `read_clipboard` / `to_clipboard` | 系统剪贴板 | ✅ | ✅ | 自写跨平台适配(xclip/pbcopy/clip,见 §3.10) | jian-io-clipboard |
| 11 | `to_latex` | LaTeX 表格 | 写 | — | 纯 JDK 自写 | jian-io-latex |
| 12 | `to_markdown` | Markdown 表格 | 写 | ✅ | 纯 JDK 自写(已含于 export) | jian-io-latex |

#### 7 种 SQL 数据库(对齐用户要求)

| # | 数据库 | JDBC 驱动 jar | 默认端口 | 备注 |
|---|---|---|---|---|
| 1 | **PostgreSQL** | `postgresql-42.7.x` | 5432 | 标准 |
| 2 | **MySQL** | `mysql-connector-j-9.7.x` | 3306 | 注意 SSL 等参数 |
| 3 | **Apache Doris** | 复用 MySQL 驱动 | 9030 | MySQL 协议兼容 |
| 4 | **SQLite** | `sqlite-jdbc-3.53.x`(自带 native) | — | 文件型,动态类型 |
| 5 | **H2** | `h2-2.4.x` | — | 内存/文件双模 |
| 6 | **Oracle** | `ojdbc11-23.26.1.0.0`(2026-02) | 1521 | NUMBER 默认 BigDecimal |
| 7 | **MS Access**(.mdb/.accdb) | `ucanaccess-5.x` + Jackcess + HSQLDB | — | 纯 Java |

#### Tier 2 —— 留接口、不实现(场景冷或 Java 圈无活跃库)

| pandas 对应 | 状态 | 理由 |
|---|---|---|
| `read_feather` / `to_feather` | 留接口,抛 `UnsupportedFormatException` | Java 圈无成熟活跃库(Arrow 的 Feather 是 Python 优先) |
| `read_stata` / `to_stata` | 同上 | 统计软件格式,Java 圈冷门 |
| `read_sas` | 同上 | 同上 |
| `read_spss` | 同上 | 同上 |
| `read_gbq` / `to_gbq`(BigQuery) | 同上 | 需 Google Cloud SDK,场景特殊 |
| `read_iceberg` / `to_iceberg` | 同上 | Apache Iceberg 表格式,需 Spark/Flink 生态 |
| `read_hdf` / `HDFStore` | 同上 | Java HDF5 库(jhdf)活跃度低且 API 不稳 |

> **留接口策略**:统一抽象 `DataFrameReader` / `DataFrameWriter` 接口,Tier 2 格式各占一个空实现,方法体抛 `UnsupportedFormatException("Feather 暂未实现,见 references/format-status.md")`,并附文档页列出"为什么不实现 + 未来如何补"。这样 API 表面对齐 pandas,但 jar 不膨胀。

### 1.3 职责边界

**做**:
- 统一 API:`Jian.readCsv/readExcel/readJson/readHtml/readXml/readSql/readParquet/readOrc/readPickle/readClipboard` + `Jian.toCsv/toExcel/...` 全套(**to\* 在顶层 Jian 门面,DataFrame 本体不挂 IO 方法**,与 pandas 的 df.to_csv 形态不同,见 §9.2)。
- 自动类型映射:外部类型 ↔ DataFrame dtype(见 §4 映射表)。
- 按需加载:用户只引用到的格式的 jar,未引的不会触发类加载。
- 流式读取大表:`iterate` / `chunk` 模式(每批 N 行),防 OOM。
- 多种写出模式:`OVERWRITE` / `APPEND` / `CREATE_OR_REPLACE` / `FAIL_IF_EXISTS`。
- Excel 多 sheet 写出 + `ExcelWriter` 上下文对象(对齐 pandas)。

**不做**:
- DataFrame 内存变换(core 的事)。
- SQL 表达式构建(jian-sql 的事)。
- Tier 2 冷门格式(留接口)。
- 复杂 ETL 编排。

### 1.4 依赖关系

```
jian-core
     ▲
     │ (单向依赖)
     │
┌────┼────┬────┬────┬────┬────┬────┬────┬────┬────┐
│    │    │    │    │    │    │    │    │    │    │
csv  excel json html xml sql  parquet orc pickle clip latex
 │    │    │    │    │    │    │    │    │    │    │
commons poi jackson jsoup JDK 各jdbc parquet orc 自写 自写 JDK
```

> **关键**:12 个 io 子模块彼此独立。引了 `jian-io-csv` 不会带入 POI/Jackson/JDBC 驱动;反之亦然。
> `jian-io-sql` 内部用反射 + ServiceLoader 探测已加载的 JDBC 驱动,**不写死任何驱动类名**。

---

## 2. 顶层 API 设计

### 2.1 读入(对齐 pandas 命名)

> ⚠️ 下方代码块为**需求示意写法**(伪代码,说明能力);**实际 API 统一为 `Xxx.read(path).配置().go()`**,可编译的完整示例见 §9.2 偏差表与本目录 `index.html` 门户的方法卡。

```java
// === 文本类 ===
Jian.readCsv("data.csv").delimiter(',').header(true).encoding("UTF-8").read();
Jian.readTable("data.tsv").delimiter('\t').read();          // = readCsv 的 TSV 别名
Jian.readFwf("data.txt").widths(10, 20, 5).read();          // 定宽

// === Excel(支持 xls/xlsx + 多 sheet)===
List<String> sheets = Jian.ExcelFile("data.xlsx").sheetNames();
DataFrame s1 = Jian.readExcel("data.xlsx").sheet("Sheet1").header(true).range("A1:D100").read();

// === JSON(支持 pandas 全部 orient)===
Jian.readJson("data.json").orient(JsonOrient.RECORDS).read();   // records / columns / values / index / split
DataFrame flat = Jian.jsonNormalize(jsonStr, "list_path");       // 嵌套展平

// === HTML(从 HTML 文件/URL 提取所有 <table>)===
List<DataFrame> tables = Html.read("page.html").match(".*用户.*").go();  // 实际 API:builder + go()
List<DataFrame> all = Jian.readHtml("page.html");                        // 门面:直接返回全部表

// === XML ===
Jian.readXml("data.xml").xpath("//row").read();

// === SQL(7 数据库通用)===
Jian.readSql(conn).query("SELECT * FROM users WHERE age > ?").params(18).read();
Jian.readSqlTable(conn, "users").schema("public").read();
Jian.readSqlQuery(conn, "SELECT id,name FROM users").read();

// === 列式二进制 ===
Jian.readParquet("data.parquet").columns("id","name").read();
Jian.readOrc("data.orc").read();

// === 序列化 ===
Jian.readPickle("data.pkl");

// === 剪贴板 ===
DataFrame clip = Jian.readClipboard();
```

### 2.2 写出(对齐 pandas)

```java
df.toCsv("out.csv").delimiter(',').header(true).encoding("UTF-8").write();

// Excel 多 sheet —— ExcelWriter 上下文(对齐 pandas)
try (ExcelWriter writer = Jian.ExcelWriter("out.xlsx")) {
    df1.toExcel(writer, "Sheet1").write();
    df2.toExcel(writer, "Sheet2").freezeHeader(true).write();
}

df.toJson("out.json").orient(JsonOrient.RECORDS).dateFormat("yyyy-MM-dd").write();
df.toHtml("out.html").index(true).border(1).classes("jian-table").write();
df.toXml("out.xml").root("rows").row("row").write();
df.toSql(conn, "users").mode(CREATE_OR_REPLACE).ifExists("replace").batchSize(1000).write();
df.toParquet("out.parquet").compression(SNAPPY).write();
df.toOrc("out.orc").compression(ZLIB).write();
df.toPickle("out.pkl");
df.toClipboard();

df.toLatex("out.tex").caption("用户表").label("tab:users").index(false).write();
df.toMarkdown("out.md").align(AUTO).write();
```

### 2.3 大表流式(防 OOM)

```java
try (DataFrameIterator it = Jian.readParquet("big.parquet")
        .columns("id","name")
        .chunkSize(10_000)
        .iterate()) {
    while (it.hasNext()) {
        DataFrame chunk = it.next();
        chunk.filter(...).toParquet("out_" + it.batchIndex() + ".parquet").write();
    }
}
```

---

## 3. 各格式实现要点

### 3.1 CSV/TSV/FWF(commons-csv)

- **依赖**:`org.apache.commons:commons-csv:1.12.0`
- CSV/TSV:用 `CSVParser`/`CSVPrinter`;TSV 即分隔符为 `\t` 的 CSV。
- **FWF(定宽)**:`commons-csv` 不直接支持,自写定宽切分逻辑(按 `widths` 数组切片)。
- 编码统一 UTF-8,可指定 GBK 等。
- 类型推断走 core 的 `Schema.infer()`。

### 3.2 Excel(poi-ooxml)

- **依赖**:`org.apache.poi:poi-ooxml:5.5.1`(**非 uber**,按原生 artifact 引用,见 AGENTS.md §2.5)。POI 的传递依赖(`poi`、`xmlbeans`、`commons-compress`、`commons-collections4` 等)由 Maven 自动拉取,不手动整合。
- 读:`WorkbookFactory.create()` 自动识别 xls/xlsx;多 sheet 通过 `ExcelFile` 枚举。
- 写:`XSSFWorkbook`(xlsx)/`HSSFWorkbook`(xls);大文件用 `SXSSFWorkbook`(流式)。
- `ExcelWriter`:封装 workbook 生命周期,支持多 DataFrame 写不同 sheet。
- 类型映射:CellType → dtype(NUMERIC→Double、STRING→String、BOOLEAN→Bool、FORMULA→求值)。
- **样式/条件格式/图表**:由 `jian-export` 模块的 Styler 子系统统一处理(见 04-export),Excel 是输出目标之一。

### 3.3 JSON(jackson-databind)

- **依赖**:`com.fasterxml.jackson.core:jackson-databind:2.18.x`
- **支持 pandas 全部 5 种 orient**:
  - `RECORDS`:`[{"a":1,"b":2},...]`
  - `COLUMNS`:`{"a":[1,...],"b":[...]}`
  - `VALUES`:`[[1,2],[3,4]]`
  - `INDEX`:`{"a":{"0":1,"1":3},"b":{...}}`
  - `SPLIT`:`{"columns":["a","b"],"index":[...],"data":[[1,2]]}`
- `jsonNormalize`:把嵌套 JSON(list of dict 中某字段是 list)展平成宽表,对齐 `pandas.json_normalize`。
- 日期默认 ISO-8601 字符串。

### 3.4 HTML(读 jsoup / 写自写)

- **读依赖**:`org.jsoup:jsoup:1.18.3`(活跃)
- 读:`jsoup` 解析 HTML,枚举所有 `<table>`,逐个转 DataFrame(支持 `<thead>`/`<tbody>`/`colspan`/`rowspan` 合并单元格的简单展开)。`match` 参数用正则筛表。
- 写:纯 JDK 拼 `<table>`,样式与 `to_html` 共用 export 模块(见 04)。
- 支持 URL 直接抓取(jsoup 自带 HTTP 取页)。

### 3.5 XML(JDK 内置 + Jackson XML)

- **读依赖**(可选):`com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.18.x`
- 读:支持 XPath 选取行节点(默认 `//row`),每个节点的属性/子元素 → 列。
- 写:每行一个 XML 元素,列名为子元素或属性(`attributeMode` 开关)。

### 3.6 SQL 通用层(JDBC,7 数据库)

**核心:一套代码,通过 JDBC 标准接口适配 7 种数据库,不写死方言。**

#### 驱动探测(关键:不写死驱动类名)

```
// ┌─ What : 探测 classpath 中已加载的 JDBC 驱动
// │  Why  : 用户按需引 jar,本模块不能假设某个驱动一定存在
// │  How  : 反射试探 DbType.driverClassName 是否可加载(不真正初始化),
// │        缓存 Set<DbType>;readSql 时从 conn.getMetaData().getDriverName() 反查 DbType
// │        不在集合中则抛 ModuleNotLoadedException 带"请引 xxx.jar"提示
```

#### 类型映射(ResultSetMetaData → dtype)

| JDBC 类型 | dtype |
|---|---|
| INTEGER/SMALLINT/TINYINT | IntColumn |
| BIGINT | LongColumn |
| FLOAT/REAL/DOUBLE | DoubleColumn |
| NUMERIC/DECIMAL | DoubleColumn(精度敏感场景可保留 BigDecimal) |
| BOOLEAN/BIT | BoolColumn |
| CHAR/VARCHAR/TEXT/CLOB | StringColumn |
| DATE | LocalDateColumn |
| TIMESTAMP/DATETIME | DateTimeColumn |
| TIME | StringColumn |
| BLOB/BINARY | 默认 skip(warning) |
| 其他 | ObjectColumn |

> 各数据库特有类型(PG JSONB、Oracle NUMBER、SQLite 动态类型、Access 的 OLE)通过 dialect 表查 JDBC 通用类型再映射,见 `references/sql-dialect.md`。

#### 7 数据库方言差异

| 数据库 | JDBC URL | 特殊点 |
|---|---|---|
| PostgreSQL | `jdbc:postgresql://h:5432/db` | JSONB→String |
| MySQL | `jdbc:mysql://h:3306/db` | useSSL 等参数 |
| Doris | `jdbc:mysql://h:9030/db` | 复用 MySQL 驱动,只读居多 |
| SQLite | `jdbc:sqlite:/path/db.sqlite` | 动态类型,按值推断 |
| H2 | `jdbc:h2:mem:test` / `jdbc:h2:file:./t` | 内存/文件 |
| Oracle | `jdbc:oracle:thin:@h:1521:orcl` | NUMBER→BigDecimal,schema 前缀 |
| Access | `jdbc:ucanaccess:///path/db.mdb` | UCanAccess;日期/布尔特殊处理 |

#### 写出(批量 INSERT)

```
// 伪代码:
//   1. CREATE_OR_REPLACE: DROP IF EXISTS + CREATE TABLE(dtype→方言列类型反向映射)
//   2. PreparedStatement INSERT ...(?,?,...) 模板
//   3. 遍历行 addBatch,每 batchSize 行 executeBatch(默认 1000)
//   4. APPEND:跳过建表;FAIL_IF_EXISTS:表存在直接抛
```

### 3.7 Parquet(parquet-java,原 parquet-mr)

- **依赖**:`org.apache.parquet:parquet-avro:1.14.x`(配 Avro schema)或 `parquet-hadoop`
- **去 Hadoop 依赖**:参考 Blake Smith 方案,用 `LocalInputFile`/`LocalOutputFile`(parquet-avro 1.12+ 支持非 Hadoop 本地文件),不拉整个 Hadoop Common。
- 压缩:支持 SNAPPY / GZIP / ZSTD / NONE。
- 列式读:`ParquetReader` 流式读 `GenericRecord` → 按字段转 Column。
- **类型映射**:Parquet 的 INT64→Long、BINARY→String、INT96→DateTime 等。

### 3.8 ORC(orc-core)

- **依赖**:`org.apache.orc:orc-core:1.9.x` + `orc-tools`
- 用 Apache ORC Core Java API(`https://orc.apache.org/docs/core-java.html`)直接读写本地文件,不依赖 Hive。
- 读:`OrcFile.openInput(file)` + `Reader.rows()` 流式遍历 `VectorizedRowBatch`。
- 写:`TypeDescription.fromString(schema)` + `Writer` 写 batch。
- 压缩:ZLIB / SNAPPY / ZSTD。

### 3.9 Pickle(自写 jian-io-pickle 格式,不依赖 Kryo/JDK 序列化)

> **关键决策**:不用 JDK 自带序列化(已被 JEP 标记废弃,不安全);不用 Kryo(活跃但有 CVE-2026-41862 反序列化漏洞)。
> 自写一套 **基于魔数头 + JSON schema + 列式二进制** 的格式,可控、安全、可跨语言。

- **格式定义**(自定义 `.jpk` 格式):
  ```
  [魔数 4字节 "JPK1"]
  [schema JSON 长度 4字节][schema JSON:columns/dtypes/index]
  [列1二进制:类型标记+长度+数据]
  [列2二进制:...]
  ...
  [CRC32 校验 4字节]
  ```
- 读写:序列化用 `DataOutputStream`;反序列化用 `DataInputStream` + 校验魔数与 CRC。
- 安全:反序列化只读数据,不实例化任意类,无 RCE 风险。
- 与 Python pickle 不互通(明确文档说明),但满足"DataFrame 落盘再加载"的核心诉求。

### 3.10 Clipboard(跨平台剪贴板,自写)

```
// ┌─ What : 把 DataFrame 写入 / 读取系统剪贴板(对齐 pandas)
// │  How  : 探测 os.name,Linux→xclip/xsel,macOS→pbcopy/pbpaste,Windows→clip/powershell Get-Clipboard
// │        数据转 TSV 格式(制表符分隔)塞给剪贴板命令
// 伪代码:
//   String os = System.getProperty("os.name").toLowerCase();
//   if (os.contains("linux"))  cmd = ["xclip", "-selection", "clipboard"];
//   else if (os.contains("mac")) cmd = ["pbcopy"];
//   else                       cmd = ["cmd", "/c", "clip"];   // 或用 PowerShell
//   探测命令存在(command -v),不存在则降级为"复制到内存临时变量"+ warning
```

> 跨平台遵循 AGENTS.md §6.7:不写死可执行文件名后缀,优先用 ProcessBuilder 让 OS 解析。

### 3.11 LaTeX(纯 JDK 自写)

- 输出 pandas 风格的 LaTeX 表格(`\begin{tabular}{lcr}` ... `\end{tabular}`)。
- 支持 caption / label / index / 列对齐(数值右、文本左)。
- 支持 booktabs 风格(`\toprule \midrule \bottomrule`,需用户 LaTeX 环境装 booktabs 宏包)。

---

## 4. 类型映射汇总

详见各格式实现要点。统一原则:
- 文件类(CSV/JSON/HTML/XML):按值推断,或用户给 schema。
- 二进制类(Parquet/ORC/Pickle):格式自带类型,按格式类型表映射。
- SQL:JDBC 类型表映射。
- 缺失值:统一按 core 的 §2.2 约定(double=NaN、其余=null)。

---

## 5. 按需加载与依赖隔离

### 5.1 不引 jar 时

- 用户只引 `jian-core` + `jian-io-csv`:CSV 正常。
- 调 `readParquet` 未引 parquet jar:抛 `ModuleNotLoadedException`,提示:
  > "请引 parquet-avro-1.14.x.jar(或 parquet-hadoop)。"

### 5.2 实现

- 每个格式一个 `XxxProvider` 实现 `DataSourceProvider` 接口,独立子模块。
- core 通过 `ServiceLoader<DataSourceProvider>` 运行时发现。
- 未加载的 Provider 不影响其他。

---

## 6. 边界与异常

| 场景 | 处理 |
|---|---|
| 文件不存在 | `FileNotFoundException`(中文提示带路径) |
| CSV 编码错误 | `MalformedInputException` |
| Excel 表头不在首行 | `headerRow(n)` 参数 |
| SQL 连接失败 | `JianSqlException`( DbType + 脱敏 URL) |
| ResultSet 含 BLOB | 默认 skip(warning) |
| 写出表已存在 + FAIL_IF_EXISTS | 抛异常 |
| Parquet/ORC schema 与 DataFrame dtype 冲突 | 优先 DataFrame dtype,做安全转换 |
| Pickle 文件魔数/CRC 校验失败 | 抛 `IOException("文件损坏或非 jian-io-pickle 格式")` |
| 剪贴板命令不存在 | 降级为内存变量 + warning,不崩溃 |
| Tier 2 格式调用 | 抛 `UnsupportedFormatException` + 文档指引 |

---

## 7. 工作量与测试

- **代码量**:自写约 7,000 行(12 格式适配 + 7 数据库方言表 + 类型映射 + pickle/clipboard/latex 自写 + ExcelWriter),测试约 4,000 行。
- **测试要求**:
  - 每个格式至少 read + write 各 1 个用例。
  - 7 数据库各 1 集成测试。
  - Parquet/ORC/Pickle 往返一致(写出再读回 == 原 DataFrame)。
  - 大表流式内存上限测试。

---

## 8. 验收标准

1. **12 类 Tier 1 格式**全部 read/write 可用(CSV/TSV/FWF/Excel/JSON/HTML/XML/SQL/Parquet/ORC/Pickle/Clipboard,LaTeX 仅写)。
2. **7 数据库**全部 read/write。
3. **JSON 5 种 orient** 全部支持,与 pandas 同输入可互认。
4. **Excel 多 sheet** + `ExcelWriter` 上下文可用。
5. 引 A 不带 B 的依赖(测试每个 io 子模块的依赖树)。
6. 调未引 jar 的功能给友好提示。
7. Tier 2 格式调到给明确"未实现"提示(不崩)。
8. Parquet/ORC/Pickle 往返一致;百万行流式 < 512MB。
9. 写出的文件能被 pandas 正确读回(类型一致)。

---

## 9. 实现说明(M3 + M4,2026-08-01;2026-08-02 安全审查更新)

> **12 格式 + 7 数据库全部已实现**(见 §9.4);下表 §9.1 为早期记录,以 §9.4 为准。

### 9.1 已实现子模块

| 子模块 | 文件 | 测试 | 状态 |
|---|---|---|---|
| `jian-io-csv` | `Csv.java`(CSV/TSV/FWF 读写,builder 模式) | `CsvTest` 12 用例 | ✅ stable |
| `jian-io-json` | `Json.java`(5 种 orient + json_normalize) | `JsonTest` 10 用例 | ✅ stable |
| `jian-io-excel` | `Excel.java`(xls/xlsx 读写 + ExcelMultiWriter 多 sheet,POI 5.5.1 原生) | `ExcelTest/ExcelTypeTest/ExcelPitfallTest` 16 用例 | ✅ stable |
| `jian-io-html` / `jian-io-xml` | `Html.java`(jsoup)/ `Xml.java`(Jackson XML) | 5 + 5 用例 | ✅ stable |
| `jian-io-sql` | `Sql.java`(7 库 JDBC,PreparedStatement 参数化) | `SqlTest/SqlMultiDbTest` 11 用例 | ✅ stable |
| `jian-io-parquet` / `jian-io-orc` | `Parquet.java` / `Orc.java`(orc-core 1.9.5 + hadoop-client-runtime) | 3 + 3 用例 | ✅ stable |
| `jian-io-pickle` / `jian-io-clipboard` / `jian-io-latex` | `.jpk`(JSON 内核 + CRC32)/ 跨平台剪贴板 / LaTeX | 4 + 2 + 1 用例 | ✅ stable |

### 9.2 与需求的偏差

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| `Jian.readCsv(path).delimiter(',').read()` | `Csv.read(path).delimiter(',').go()` | Java 命名:用 `Csv` 类承载 builder,`go()` 终结(避免与 Java 关键字 read 冲突且更明确);顶层 `Jian` 门面留 M4.3 统一聚合 |
| `Jian.readJson(path).orient(RECORDS).read()` | `Json.read(path).orient(Orient.RECORDS).go()` | 同上 |
| `pd.ExcelWriter` 上下文对象 | `Excel.writer(path)` 返回 `ExcelMultiWriter`(try-with-resources) | Java 风格,AutoCloseable 替代 Python with |
| FWF 用 commons-csv | FWF **自写切片**(commons-csv 不支持 FWF,规范 §3.1 已预告) | 按 widths 数组逐行 substring |
| 5 种 orient 全实现 | 全实现 + 往返一致 | RECORDS/COLUMNS/VALUES/INDEX/SPLIT |

### 9.3 类型推断细节(实测)

CSV/JSON 读回的值统一为字符串,经 `Schema.infer` 推断:
- "1"/"2"/"3"(int 范围)→ **INT**(不是 LONG,即使原 DataFrame 是 LONG);
- "100000000000"(超 int)→ LONG;
- "1.5"/"2e3" → DOUBLE;
- "true"/"false" → BOOL;
- "2026-08-01" → DATE;"2026-08-01 12:30:00" → DATETIME;
- 其它 → STRING。

> 注:CSV 往返时,LONG 列可能被推断为 INT(值在 int 范围)。DataFrame.getLongColumn 已兼容(INT/LONG 自动转换),用户无需关心。

### 9.4 实现状态(全部已落地)

12 格式 + 7 数据库**全部已实现**(含 SQLite/H2 真实验证、ORC 经 hadoop-client-runtime 解决):
- CSV/TSV/FWF、Excel(xls/xlsx,含**逐列类型精确推断**:整→Long/小→Double/混合→String/日期→ISO)
- JSON(5 orient)、HTML(jsoup)、XML(Jackson)、SQL(SQLite+H2+PG/MySQL 模拟)
- Parquet/ORC(列存往返一致)、Pickle(.jpk)、Clipboard(跨平台)、LaTeX/Markdown
- 顶层 Jian 门面(jian-facade):`Jian.read/write` 按扩展名自动分发

---

### 9.5 已知未实现项(留 v2,文档不再声称已做)

- `Sql.readSqlTable(conn, table, schema)` 的 schema 参数、`toSql` 的 batchSize 可配:未实现(batchSize 内部固定 1000,表名/库名由调用方拼)。
- Excel `range("A1:D100")` 区域读、`headerRow(n)` 表头行、`freezeHeader` 冻结表头:SXSSF 流式写与这些配置项留 v2(XSSF 覆盖 95% 场景)。
- XML XPath 选行、`attributeMode` 属性列模式:未实现(仅 rowName 递归查找)。
- Parquet/ORC 压缩参数(compression codec)可配:未实现(默认 SNAPPY)。
- HTML colspan/rowspan 合并单元格展开:未实现(纯取单元格文本)。
- Tier 2 格式(Feather/Stata/SAS/SPSS/GBQ/Iceberg/HDF5)与 `UnsupportedFormatException`:留接口不实现。

### 9.6 安全与健壮性修复(2026-08-02 全项目审查)

- **CSV 公式注入防护(OWASP)**:`Csv.write(...).go()` 默认把 `= + - @` 开头的值前缀 `'` 防 Excel/WPS 当公式执行;可用 `.sanitizeFormulas(false)` 关闭。
- **XML 写端名称清洗**:列名/root/row 名称里的非法字符(`& < > 空格` 等)替换为 `_`(转义在 XML 名称中无效),值文本转义 `& < >`,保证产物永远合法可解析。
- **jian-io-sql 参数化**:读(查询)与写(INSERT)全部走 `PreparedStatement` + `?` 占位,无字符串拼接值注入;表名/列名由调用方提供(与 pandas to_sql 同约定)。
- **Pickle 反序列化安全**:`.jpk` 为魔数 + JSON + CRC32 的自定义格式,不实例化任意类,无 RCE 风险。
- **门面 .tsv 分支补齐**:修复 `Jian.read/write` 对 `.tsv` 无分支却提示"支持"的矛盾(现按制表符读写)。
- **门面方法补齐**:`readOrc/readPickle/readSqlQuery/readTable/readFwf` + `toSql/toOrc/toPickle/toClipboard/toLatex/toMarkdown/toHtml` + `jsonNormalize`。

---

*本分册独立,与 01/03-06 无耦合。覆盖 pandas 主流 IO 接口矩阵;冷门统计格式留接口不实现。*
*M3 + M4:全 12 格式(CSV/Excel/JSON/HTML/XML/SQL/Parquet/Pickle/Clipboard/LaTeX;ORC 完整实现(orc-core 1.9.5 + hadoop-client-runtime + protobuf))实现完成于 2026-08-01;2026-08-02 安全审查后 72 个 io 测试全过。*
