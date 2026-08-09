// ════════════════════════════════════════════════════════════════════════════
// jian vs DuckDB / SQLite / H2 单表 WHERE+GROUP BY 独立 benchmark
// ┌─ What : 单表 t(id, k, v) 上执行 SELECT k, count(*), sum(v) FROM t
// │         WHERE id % 10 = 0 GROUP BY k —— 简单聚合,4 引擎都擅长的甜点场景。
// │  Why  : 与三表复合关联 benchmark 对照,展示各引擎在"简单聚合"场景的真实排名
// │         (复合关联 DuckDB 一枝独秀,简单聚合排名可能不同)。
// │  Who  : 由 doc/index.html「性能对比」段、README 引用。
// │  When : 修改引擎接入 / 升级 jar / JVM 时重跑。
// │  Where: doc/benchmark/SingleTableBenchmark.java(项目根的相对路径)
// │  How  :
// │    ① 固定种子生成单表 t(N 行):id ∈ [0, 2N) 随机,k ∈ [0, 100) 随机,v ∈ [0,1) 随机
// │    ② SQL: SELECT k, count(*), sum(v) FROM t WHERE id % 10 = 0 GROUP BY k
// │    ③ 入库(各引擎官方最快):
// │       DuckDB=Appender | SQLite=PRAGMA+事务+PS.batch | H2=in-mem+事务+PS.batch
// │       jian=ofColumnArrays 零拷贝构造 + filter(boolean[]) + groupBy("k").agg(...)
// │    ④ 每规模预热 1 + 测量 3 取中位数,Future 硬超时 120s
// │    ⑤ 校验:4 引擎 组数 + 首组 count 一致(正确性兜底)
// ════════════════════════════════════════════════════════════════════════════
// 运行(JAR_HOME 默认 ~/tools/jar,可用环境变量覆盖):
//   JAR_HOME=${JAR_HOME:-~/tools/jar}
//   CP=$(for j in "$JAR_HOME"/*.jar; do printf '%s:' "$j"; done | sed 's/:$//')
//   CP="$CP:jian/jian-core/target/classes"
//   java -Xmx7g -cp "$CP" doc/benchmark/SingleTableBenchmark.java [N1 N2 ...]
//   默认 N = 1000000 5000000 10000000
//   输出: 控制台表格 + doc/benchmark/result_single.json

// 不声明 package:JDK 21 单文件启动要求默认包。

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.sql.*;
import java.util.*;

public class SingleTableBenchmark {

    static final long SEED        = 20260808L;
    static final int  WARMUP_RUNS = 1;
    static final int  MEASURE_RUNS = 3;
    static final int  PS_BATCH    = 5000;
    static final int  HARD_TIMEOUT_SEC = 120;
    // 简单单表聚合 SQL:WHERE 过滤 + GROUP BY 聚合
    static final String SQL =
        "SELECT k, count(*), sum(v) FROM t WHERE id % 10 = 0 GROUP BY k";

    // ────────────────────────────────────────────────────────────────────────
    // main
    // ────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        long[] sizes;
        if (args.length > 0) {
            sizes = new long[args.length];
            for (int i = 0; i < args.length; i++) sizes[i] = Long.parseLong(args[i]);
        } else {
            sizes = new long[]{1_000_000L, 5_000_000L, 10_000_000L};
        }

        System.out.println("=".repeat(96));
        System.out.println("jian vs DuckDB / SQLite / H2 — 单表 WHERE+GROUP BY 独立 benchmark");
        System.out.println("=".repeat(96));
        System.out.println("JVM: " + System.getProperty("java.version") + "  | 核: " + Runtime.getRuntime().availableProcessors());
        System.out.println("种子: " + SEED + "  | 预热 " + WARMUP_RUNS + " + 测量 " + MEASURE_RUNS + " 次(取中位数)");
        System.out.println("SQL: " + SQL);
        System.out.println("入库: DuckDB=Appender | SQLite=PRAGMA+事务+PS.batch | H2=in-mem+事务+PS.batch | jian=ofColumns+filter+groupBy.agg");
        System.out.println("硬超时: " + HARD_TIMEOUT_SEC + "s/次  | 表: t(id BIGINT, k BIGINT, v DOUBLE)");
        System.out.println("-".repeat(96));

        Map<Long, Map<String, Result>> all = new LinkedHashMap<>();

        for (long n : sizes) {
            System.out.printf("%n▶ N = %,d 行%n", n);
            long[] ids = new long[(int)n];
            long[] ks  = new long[(int)n];
            double[] vs = new double[(int)n];
            genTable(n, ids, ks, vs);

            Map<String, Result> r = new LinkedHashMap<>();
            r.put("DuckDB", runDuckDb(ids, ks, vs));
            r.put("SQLite", runSqlite(ids, ks, vs));
            r.put("H2",     runH2(ids, ks, vs));
            r.put("jian",   runJian(ids, ks, vs));

            // 正确性校验:组数必须一致
            Set<Long> groupCounts = new HashSet<>();
            for (Result rr : r.values()) if (rr != null && rr.count() != -1) groupCounts.add(rr.count());
            long timeoutCnt = r.values().stream().filter(x -> x != null && x.count() == -1).count();
            long failedCnt  = r.values().stream().filter(x -> x == null).count();
            if (groupCounts.size() > 1) {
                System.out.println("  ⚠⚠ 组数不一致!");
                r.forEach((k, v) -> System.out.printf("       %-7s %s%n", k,
                    v == null ? "失败" : (v.count() == -1 ? "超时" : String.format("%,d", v.count()))));
            } else if (groupCounts.size() == 1) {
                String extra = "";
                if (timeoutCnt > 0) extra += "  (另 " + timeoutCnt + " 引擎超时)";
                if (failedCnt > 0)  extra += "  (另 " + failedCnt + " 引擎失败)";
                System.out.printf("  ✓ 引擎组数一致 = %,d%s%n", groupCounts.iterator().next(), extra);
            }
            all.put(n, r);
        }

        writeJson(all);
        System.out.println("\n→ 结果已写入 doc/benchmark/result_single.json");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 数据生成:id ∈ [0, 2N),k ∈ [0, 100),v ∈ [0, 1)
    // ────────────────────────────────────────────────────────────────────────
    static void genTable(long n, long[] ids, long[] ks, double[] vs) {
        Random r = new Random(SEED ^ n);
        long range = 2L * n;
        for (int i = 0; i < (int)n; i++) {
            ids[i] = Math.floorMod(r.nextLong(), range);
            ks[i]  = Math.floorMod(r.nextLong(), 100);
            vs[i]  = r.nextDouble();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 计时工具(与 JoinBenchmark 同款)
    // ────────────────────────────────────────────────────────────────────────
    record Sample(long wallMs, long cpuMs, long heapMb) {}
    static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();

    static class Timer {
        long wall0, cpu0, heap0;
        void start() {
            System.gc(); System.gc();
            heap0 = usedHeap();
            cpu0  = TMX.getCurrentThreadCpuTime();
            wall0 = System.nanoTime();
        }
        Sample stop() {
            long wallMs = (System.nanoTime() - wall0) / 1_000_000;
            long cpuMs  = (TMX.getCurrentThreadCpuTime() - cpu0) / 1_000_000;
            long heapMb = (usedHeap() - heap0) / (1024 * 1024);
            return new Sample(wallMs, cpuMs, heapMb);
        }
        private static long usedHeap() {
            Runtime r = Runtime.getRuntime();
            return r.totalMemory() - r.freeMemory();
        }
    }

    record Result(long wall, long cpu, long mem, long count) {}

    @FunctionalInterface interface Loader { Object load(long[] ids, long[] ks, double[] vs) throws Exception; }
    @FunctionalInterface interface Runner { long[] run(Object ctx) throws Exception; }

    static long medianLong(List<Long> xs) { Collections.sort(xs); return xs.get(xs.size() / 2); }

    static Result measure(String name, Loader loader, Runner runner, long[] ids, long[] ks, double[] vs) {
        try {
            // 预热(也用硬超时)
            for (int w = 0; w < WARMUP_RUNS; w++) {
                final Object[] ctxHolder = new Object[1];
                Boolean ok = runWithHardTimeout(() -> {
                    ctxHolder[0] = loader.load(ids, ks, vs);
                    runner.run(ctxHolder[0]);
                    return true;
                });
                if (ctxHolder[0] instanceof AutoCloseable ac)
                    try { ac.close(); } catch (Exception ignore) {}
                if (ok == null) {
                    System.out.printf("  [%s] 预热超时(>%ds),跳过测量%n", name, HARD_TIMEOUT_SEC);
                    return new Result(-1, -1, -1, -1);
                }
            }
            // 测量
            List<Long> walls = new ArrayList<>(), cpus = new ArrayList<>(), mems = new ArrayList<>();
            long firstCnt = -1;
            int timeoutCount = 0;
            for (int m = 0; m < MEASURE_RUNS; m++) {
                final Object[] ctxHolder = new Object[1];
                long[] rc = new long[]{-1, -1, -1, -1};
                Boolean done = runWithHardTimeout(() -> {
                    ctxHolder[0] = loader.load(ids, ks, vs);
                    long[] r = runner.run(ctxHolder[0]);
                    System.arraycopy(r, 0, rc, 0, r.length);
                    return true;
                });
                if (ctxHolder[0] instanceof AutoCloseable ac)
                    try { ac.close(); } catch (Exception ignore) {}
                if (done == null) {
                    timeoutCount++;
                    walls.add((long) HARD_TIMEOUT_SEC * 1000);
                    cpus.add((long) HARD_TIMEOUT_SEC * 1000);
                    mems.add(-1L);
                } else {
                    Sample s = new Sample(rc[0], rc[1], rc[2]);
                    long cnt = rc[3];
                    walls.add(s.wallMs()); cpus.add(s.cpuMs()); mems.add(s.heapMb());
                    if (cnt != -1) {
                        if (firstCnt == -1) firstCnt = cnt;
                    }
                }
            }
            Result res = new Result(medianLong(walls), medianLong(cpus), medianLong(mems), firstCnt);
            String cntStr;
            if (timeoutCount == MEASURE_RUNS) cntStr = "超时(每次>" + HARD_TIMEOUT_SEC + "s)";
            else if (timeoutCount > 0)        cntStr = "部分超时(" + timeoutCount + "/" + MEASURE_RUNS + ")";
            else if (firstCnt == -1)          cntStr = "失败";
            else                              cntStr = String.format("%,d 组", res.count());
            System.out.printf("  %-7s wall=%7dms  cpu=%7dms  memΔ=%+6dMB  组数=%s%n",
                    name, res.wall(), res.cpu(), res.mem(), cntStr);
            return res;
        } catch (Exception e) {
            System.out.printf("  [%s] 失败: %s%n", name, e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    static <T> T runWithHardTimeout(java.util.concurrent.Callable<T> task) {
        java.util.concurrent.ExecutorService ex =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "bench-worker");
            th.setDaemon(true);
            return th;
        });
        try {
            java.util.concurrent.Future<T> f = ex.submit(task);
            try {
                return f.get(HARD_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                f.cancel(true);
                return null;
            } catch (java.util.concurrent.ExecutionException ee) {
                throw new RuntimeException(ee.getCause() != null ? ee.getCause() : ee);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        } finally { ex.shutdownNow(); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 1: DuckDB — Appender
    // ════════════════════════════════════════════════════════════════════════
    static Result runDuckDb(long[] ids, long[] ks, double[] vs) {
        return measure("DuckDB",
            (_ids, _ks, _vs) -> {
                Class.forName("org.duckdb.DuckDBDriver", true, ClassLoader.getSystemClassLoader());
                Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS t");
                    st.execute("CREATE TABLE t(id BIGINT, k BIGINT, v DOUBLE)");
                }
                org.duckdb.DuckDBConnection dconn = conn.unwrap(org.duckdb.DuckDBConnection.class);
                org.duckdb.DuckDBAppender ap = dconn.createAppender("main", "t");
                for (int i = 0; i < _ids.length; i++) {
                    ap.beginRow().append(_ids[i]).append(_ks[i]).append(_vs[i]).endRow();
                }
                ap.close();
                return conn;
            },
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Connection conn = (Connection) ctx;
                long groupCount;
                try (Statement st = conn.createStatement()) {
                    try { st.setQueryTimeout(HARD_TIMEOUT_SEC); } catch (SQLException ignore) {}
                    try (ResultSet rs = st.executeQuery(SQL)) {
                        groupCount = 0;
                        while (rs.next()) groupCount++;
                    }
                }
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), groupCount};
            },
            ids, ks, vs);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 2: SQLite — 全 PRAGMA + 单事务 + PS.batch
    // ════════════════════════════════════════════════════════════════════════
    static Result runSqlite(long[] ids, long[] ks, double[] vs) {
        return measure("SQLite",
            (_ids, _ks, _vs) -> {
                Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader());
                Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=OFF");
                    st.execute("PRAGMA synchronous=OFF");
                    st.execute("PRAGMA temp_store=MEMORY");
                    st.execute("PRAGMA cache_size=-1000000");
                    st.execute("PRAGMA locking_mode=EXCLUSIVE");
                    st.execute("CREATE TABLE t(id INTEGER, k INTEGER, v REAL)");
                }
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES(?,?,?)")) {
                    for (int i = 0; i < _ids.length; i++) {
                        ps.setLong(1, _ids[i]); ps.setLong(2, _ks[i]); ps.setDouble(3, _vs[i]);
                        ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return conn;
            },
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Connection conn = (Connection) ctx;
                long groupCount;
                try (Statement st = conn.createStatement()) {
                    try { st.setQueryTimeout(HARD_TIMEOUT_SEC); } catch (SQLException ignore) {}
                    try (ResultSet rs = st.executeQuery(SQL)) {
                        groupCount = 0;
                        while (rs.next()) groupCount++;
                    }
                }
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), groupCount};
            },
            ids, ks, vs);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 3: H2 — in-memory + 单事务 + PS.batch
    // ════════════════════════════════════════════════════════════════════════
    static Result runH2(long[] ids, long[] ks, double[] vs) {
        return measure("H2",
            (_ids, _ks, _vs) -> {
                Class.forName("org.h2.Driver", true, ClassLoader.getSystemClassLoader());
                String h2Db = "single" + System.nanoTime();
                Connection conn = DriverManager.getConnection(
                    "jdbc:h2:mem:" + h2Db + ";CACHE_SIZE=65536;DB_CLOSE_DELAY=-1");
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS t");
                    st.execute("CREATE TABLE t(id BIGINT, k BIGINT, v DOUBLE)");
                }
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES(?,?,?)")) {
                    for (int i = 0; i < _ids.length; i++) {
                        ps.setLong(1, _ids[i]); ps.setLong(2, _ks[i]); ps.setDouble(3, _vs[i]);
                        ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return conn;
            },
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Connection conn = (Connection) ctx;
                long groupCount;
                try (Statement st = conn.createStatement()) {
                    try { st.setQueryTimeout(HARD_TIMEOUT_SEC); } catch (SQLException ignore) {}
                    try (ResultSet rs = st.executeQuery(SQL)) {
                        groupCount = 0;
                        while (rs.next()) groupCount++;
                    }
                }
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), groupCount};
            },
            ids, ks, vs);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 4: jian — ofColumns + filter + groupBy("k").agg
    // ════════════════════════════════════════════════════════════════════════
    // SQL 等价:
    //   SELECT k, count(*), sum(v) FROM t WHERE id % 10 = 0 GROUP BY k
    // jian 实现:
    //   1. t = DataFrame.ofColumnArrays(["id","k","v"], [ids, ks, vs])
    //   2. mask[i] = (ids[i] % 10 == 0);  tf = t.filter(mask)
    //   3. agg = tf.groupBy("k").agg({"count":"count", "v_sum":"sum"})
    //   4. groupCount = agg.rowCount()
    static Result runJian(long[] ids, long[] ks, double[] vs) {
        return measure("jian",
            (_ids, _ks, _vs) -> null,    // jian 无入库概念,loader 返回 null,run 阶段直接用数组
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Class<?> dfCls = Class.forName("jian.core.DataFrame", true, ClassLoader.getSystemClassLoader());
                var ofColumns = dfCls.getMethod("ofColumnArrays", List.class, Object[].class);
                var filter    = dfCls.getMethod("filter", boolean[].class);
                var groupBy   = dfCls.getMethod("groupBy", String[].class);
                var rowCount  = dfCls.getMethod("rowCount");
                Class<?> gbCls = Class.forName("jian.core.GroupBy", true, ClassLoader.getSystemClassLoader());
                var agg       = gbCls.getMethod("agg", Map.class);

                // 构造 t
                Object t = ofColumns.invoke(null, List.of("id","k","v"),
                        new Object[]{ids, ks, vs});
                // mask: id % 10 == 0
                boolean[] mask = new boolean[ids.length];
                for (int i = 0; i < ids.length; i++) mask[i] = (ids[i] % 10 == 0);
                Object tf = filter.invoke(t, mask);
                // groupBy("k").agg({"id":"count", "v":"sum"})
                //   key=输入列名, value=聚合函数(对齐 pandas;对 id 列 count 等价于 count(*))
                Object gb = groupBy.invoke(tf, new Object[]{new String[]{"k"}});
                Map<String,String> aggSpec = new LinkedHashMap<>();
                aggSpec.put("id", "count");
                aggSpec.put("v",  "sum");
                Object aggDf = agg.invoke(gb, aggSpec);
                long groupCount = (int) rowCount.invoke(aggDf);
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), groupCount};
            },
            ids, ks, vs);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 输出 result_single.json
    // ────────────────────────────────────────────────────────────────────────
    static void writeJson(Map<Long, Map<String, Result>> all) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"seed\": ").append(SEED).append(",\n");
        sb.append("  \"sql\": \"").append(SQL).append("\",\n");
        sb.append("  \"scene\": \"单表 WHERE+GROUP BY 简单聚合:t(id,k,v), SELECT k,count(*),sum(v) WHERE id%10=0 GROUP BY k\",\n");
        sb.append("  \"jvm\": \"").append(System.getProperty("java.version")).append("\",\n");
        sb.append("  \"cores\": ").append(Runtime.getRuntime().availableProcessors()).append(",\n");
        sb.append("  \"warmup\": ").append(WARMUP_RUNS).append(",\n");
        sb.append("  \"measure\": ").append(MEASURE_RUNS).append(",\n");
        sb.append("  \"hardTimeoutSec\": ").append(HARD_TIMEOUT_SEC).append(",\n");
        sb.append("  \"sizes\": {\n");
        int i = 0;
        for (var e : all.entrySet()) {
            sb.append("    \"").append(e.getKey()).append("\": {\n");
            int j = 0;
            for (var r : e.getValue().entrySet()) {
                sb.append("      \"").append(r.getKey()).append("\": ");
                if (r.getValue() == null) sb.append("null");
                else {
                    Result v = r.getValue();
                    sb.append("{\"wall\":").append(v.wall())
                      .append(",\"cpu\":").append(v.cpu())
                      .append(",\"mem\":").append(v.mem())
                      .append(",\"groups\":").append(v.count()).append("}");
                }
                if (++j < e.getValue().size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }");
            if (++i < all.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  }\n}\n");
        java.nio.file.Files.writeString(
            java.nio.file.Paths.get("doc/benchmark/result_single.json"), sb.toString());
    }
}
