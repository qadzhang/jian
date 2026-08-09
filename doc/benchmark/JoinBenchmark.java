// ════════════════════════════════════════════════════════════════════════════
// jian vs DuckDB / SQLite / H2 三表 复合关联 公平 benchmark(含/无索引对照)
// ┌─ What : 对比 4 个引擎在三表 JOIN(数字求和 + 字符串拼接 复合关联)场景下,
// │         「无索引」与「有索引」两种模式 各自的 wall/CPU/堆。
// │  Why  : 用户要求"数据库的有无索引都加上测试",看清索引对复合关联的影响。
// │         复合关联 = (b.ba+b.bb=c.k1) AND (b.bc||b.bd=c.k2),其中 a-b 段是原列
// │         等值关联(可用索引),b-c 段是计算表达式关联(普通索引用不上,需表达式索引)。
// │  Who  : doc/00-overview.md §10.x、doc/index.html「性能对比」段、README 引用。
// │  When : 修改引擎接入 / 升级 jar / JVM 时重跑。
// │  Where: doc/benchmark/JoinBenchmark.java(项目根的相对路径)
// │  How  :
// │    ① 固定种子生成 a/b/c:
// │       a: id BIGINT (id ∈ [0,2N))
// │       b: id, ba, bb(DOUBLE), bc, bd(VARCHAR) — ba/bb 数字求和, bc/bd 字符串拼接
// │       c: k1(=ba+bb), k2(=bc||bd), val — 前 80% b 行派生,保证 80% 匹配
// │    ② SQL: SELECT count(*) FROM a JOIN b ON a.id=b.id
// │           JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc||b.bd)=c.k2
// │    ③ 两种索引模式:
// │       - no-index:不建任何索引(看 baseline)
// │       - with-index:
// │         · a(id)、b(id)、c(k1)、c(k2) 普通 B-tree(对 a-b 段有用)
// │         · SQLite/DuckDB 额外建表达式索引 b(ba+bb)、b(bc||bd)(对 b-c 段有用)
// │         · H2 不支持表达式索引(只在原列上建)
// │         · jian 无索引概念(两次结果相同,作为对照基线)
// │    ④ 入库(各引擎官方最快):
// │       DuckDB=Appender | SQLite=PRAGMA+事务+PS.batch | H2=in-mem+事务+PS.batch
// │       jian=ofColumnArrays+colAdd+assign+merge(多键)
// │    ⑤ 每规模预热 1 + 测量 3 取中位数;4 引擎 count 必须一致(正确性兜底)
// ════════════════════════════════════════════════════════════════════════════
// 运行(JAR_HOME 默认 ~/tools/jar,可用环境变量覆盖):
//   JAR_HOME=${JAR_HOME:-~/tools/jar}
//   CP=$(for j in "$JAR_HOME"/*.jar; do printf '%s:' "$j"; done | sed 's/:$//')
//   CP="$CP:jian/jian-core/target/classes"
//   java -Xmx6g -cp "$CP" doc/benchmark/JoinBenchmark.java [N1 N2 ...]
//   默认 N = 100000 500000 5000000
//   输出: 控制台表格 + doc/benchmark/result.json

// 不声明 package:JDK 21 单文件启动要求默认包。

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.sql.*;
import java.util.*;

public class JoinBenchmark {

    static final long SEED        = 20260808L;
    static final int  WARMUP_RUNS = 1;
    static final int  MEASURE_RUNS = 3;
    static final int  PS_BATCH    = 5000;
    static final String SQL =
        "SELECT count(*) FROM a JOIN b ON a.id=b.id " +
        "JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc||b.bd)=c.k2";

    // ────────────────────────────────────────────────────────────────────────
    // main
    // ────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        long[] sizes;
        if (args.length > 0) {
            sizes = new long[args.length];
            for (int i = 0; i < args.length; i++) sizes[i] = Long.parseLong(args[i]);
        } else {
            sizes = new long[]{100_000L, 500_000L, 5_000_000L};
        }

        System.out.println("=".repeat(100));
        System.out.println("jian vs DuckDB / SQLite / H2 — 三表 复合关联 benchmark(无索引 vs 有索引)");
        System.out.println("=".repeat(100));
        System.out.println("JVM: " + System.getProperty("java.version") + "  | 核: " + Runtime.getRuntime().availableProcessors());
        System.out.println("种子: " + SEED + "  | 预热 " + WARMUP_RUNS + " + 测量 " + MEASURE_RUNS + " 次(取中位数)");
        System.out.println("SQL: " + SQL);
        System.out.println("入库: DuckDB=Appender | SQLite=PRAGMA+事务+PS.batch | H2=in-mem+事务+PS.batch | jian=ofColumns+colAdd+assign+merge(多键)");
        System.out.println("-".repeat(100));

        // 结果结构: mode → size → engine → Result
        Map<String, Map<Long, Map<String, Result>>> all = new LinkedHashMap<>();

        for (String mode : new String[]{"no-index", "with-index"}) {
            System.out.println("\n" + "█".repeat(48) + "  模式: " + mode + "  " + "█".repeat(48 - mode.length()));
            Map<Long, Map<String, Result>> modeRes = new LinkedHashMap<>();
            for (long n : sizes) {
                System.out.printf("%n▶ N = %,d  (a/b 各 %,d 行, c 取 b 前 80%% 派生)%n", n, n);
                Tables t = genTables(n);
                boolean withIdx = mode.equals("with-index");
                Map<String, Result> r = new LinkedHashMap<>();
                r.put("DuckDB", runDuckDb(t, withIdx));
                r.put("SQLite", runSqlite(t, withIdx));
                r.put("H2",     runH2(t, withIdx));
                r.put("jian",   runJian(t, withIdx));
                // 正确性校验:跳过超时(-1)和失败(null)的引擎
                Set<Long> counts = new HashSet<>();
                for (Result rr : r.values()) if (rr != null && rr.count() != -1) counts.add(rr.count());
                long timeoutCnt = r.values().stream().filter(x -> x != null && x.count() == -1).count();
                long failedCnt  = r.values().stream().filter(x -> x == null).count();
                if (counts.size() > 1) {
                    System.out.println("  ⚠⚠ count 不一致!");
                    r.forEach((k, v) -> System.out.printf("       %-7s %s%n", k,
                        v == null ? "失败" : (v.count() == -1 ? "超时" : String.format("%,d", v.count()))));
                } else if (counts.size() == 1) {
                    String extra = "";
                    if (timeoutCnt > 0) extra += "  (另 " + timeoutCnt + " 引擎超时)";
                    if (failedCnt > 0)  extra += "  (另 " + failedCnt + " 引擎失败)";
                    System.out.printf("  ✓ 引擎 count 一致 = %,d%s%n", counts.iterator().next(), extra);
                }
                modeRes.put(n, r);
            }
            all.put(mode, modeRes);
        }

        writeJson(all);
        System.out.println("\n→ 结果已写入 doc/benchmark/result.json");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 数据生成
    // ────────────────────────────────────────────────────────────────────────
    // 伪代码:
    //   1. Random(SEED ^ N) 保证可复现
    //   2. a: N 行, id ∈ [0, 2N) 均匀随机
    //   3. b: N 行, id ∈ [0, 2N) 随机; ba,bb ∈ [0,1000) 随机小数; bc="p"+i%100, bd="_"+i%50
    //   4. c: 取 b 前 80% 行派生 → k1=ba+bb, k2=bc||bd(保证 80% 匹配)
    static Tables genTables(long n) {
        Random r = new Random(SEED ^ n);
        long range = 2L * n;
        int N = (int) n;
        long[] aId = new long[N];
        for (int i = 0; i < N; i++) aId[i] = Math.floorMod(r.nextLong(), range);
        long[]   bId = new long[N];
        double[] bA  = new double[N], bB = new double[N];
        String[] bC  = new String[N], bD = new String[N];
        for (int i = 0; i < N; i++) {
            bId[i] = Math.floorMod(r.nextLong(), range);
            bA[i]  = r.nextInt(1000);
            bB[i]  = r.nextInt(1000);
            bC[i]  = "p" + (i % 100);
            bD[i]  = "_" + (i % 50);
        }
        int cn = (int) (N * 8 / 10);
        double[] cK1 = new double[cn];
        String[] cK2 = new String[cn];
        double[] cV  = new double[cn];
        for (int i = 0; i < cn; i++) {
            cK1[i] = bA[i] + bB[i];
            cK2[i] = bC[i] + bD[i];
            cV[i]  = r.nextDouble();
        }
        return new Tables(aId, bId, bA, bB, bC, bD, cK1, cK2, cV);
    }

    record Tables(
        long[] aId,
        long[] bId, double[] bA, double[] bB, String[] bC, String[] bD,
        double[] cK1, String[] cK2, double[] cV
    ) {}

    // ────────────────────────────────────────────────────────────────────────
    // 计时
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

    @FunctionalInterface interface Loader { Object load(Tables t) throws Exception; }
    @FunctionalInterface interface Runner  { long[] run(Object ctx) throws Exception; }

    static long medianLong(List<Long> xs) { Collections.sort(xs); return xs.get(xs.size() / 2); }

    /** 单引擎单次测量整体硬超时(秒)。本场景的复合表达式关联对某些引擎(H2 无索引)
     *  是 O(N²) 灾难,H2 的 setQueryTimeout 在嵌套循环里不强制中断,故用线程级硬超时兜底。
     *  超时后线程被 interrupt,结果记为 -1(超时)。*/
    static final int HARD_TIMEOUT_SEC = 60;

    /** 执行查询 SQL 并返回 count。调用方负责整体硬超时(线程级)。*/
    static long execCountQuery(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            try { st.setQueryTimeout(HARD_TIMEOUT_SEC); } catch (SQLException ignore) {}
            try (ResultSet rs = st.executeQuery(SQL)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static Result measure(String name, Loader loader, Runner runner, Tables t) {
        try {
            // 预热(也用硬超时,避免预热阶段就卡死)
            for (int w = 0; w < WARMUP_RUNS; w++) {
                final Object[] ctxHolder = new Object[1];
                Boolean ok = runWithHardTimeout(() -> {
                    ctxHolder[0] = loader.load(t);
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
                    ctxHolder[0] = loader.load(t);
                    long[] r = runner.run(ctxHolder[0]);
                    System.arraycopy(r, 0, rc, 0, r.length);
                    return true;
                });
                if (ctxHolder[0] instanceof AutoCloseable ac)
                    try { ac.close(); } catch (Exception ignore) {}
                if (done == null) {
                    // 硬超时
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
                        else if (cnt != firstCnt)
                            System.out.printf("  [%s] ⚠ count 不一致 %d vs %d%n", name, firstCnt, cnt);
                    }
                }
            }
            Result res = new Result(medianLong(walls), medianLong(cpus), medianLong(mems), firstCnt);
            String cntStr;
            if (timeoutCount == MEASURE_RUNS) cntStr = "超时(每次>" + HARD_TIMEOUT_SEC + "s)";
            else if (timeoutCount > 0)        cntStr = "部分超时(" + timeoutCount + "/" + MEASURE_RUNS + ")";
            else if (firstCnt == -1)          cntStr = "失败";
            else                              cntStr = String.format("%,d", res.count());
            System.out.printf("  %-7s wall=%7dms  cpu=%7dms  memΔ=%+6dMB  count=%s%n",
                    name, res.wall(), res.cpu(), res.mem(), cntStr);
            return res;
        } catch (Exception e) {
            System.out.printf("  [%s] 失败: %s%n", name, e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** 在独立线程跑 task,超过 HARD_TIMEOUT_SEC 就 cancel(true) 中断并返回 null。
     *  H2 等引擎的 setQueryTimeout 在 nested loop 里不强制生效,这是兜底。*/
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
        } finally {
            ex.shutdownNow();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 索引 SQL 生成(各引擎统一入口)
    // ────────────────────────────────────────────────────────────────────────
    // 伪代码:
    //   1. 共通: a(id)、b(id)、c(k1)、c(k2) 普通 B-tree(对 a-b 段关联有用)
    //   2. SQLite/DuckDB 额外表达式索引 b(ba+bb)、b(bc||bd)(对 b-c 段有用)
    //   3. H2 不支持表达式索引,只建原列索引
    static void createIndexes(Statement st, String engine) throws SQLException {
        // 共通:原列索引(a-b 段 + c 段)
        st.execute("CREATE INDEX idx_a_id ON a(id)");
        st.execute("CREATE INDEX idx_b_id ON b(id)");
        st.execute("CREATE INDEX idx_c_k1 ON c(k1)");
        st.execute("CREATE INDEX idx_c_k2 ON c(k2)");
        // 表达式索引:SQLite/DuckDB 支持,H2 不支持(会抛错,故排除)
        if (!engine.equals("H2")) {
            try {
                st.execute("CREATE INDEX idx_b_sum ON b(ba+bb)");
                st.execute("CREATE INDEX idx_b_cat ON b(bc||bd)");
            } catch (SQLException ignore) {
                // 某些引擎/版本可能不支持表达式索引,忽略(降级为无表达式索引)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 1: DuckDB — Appender
    // ════════════════════════════════════════════════════════════════════════
    static Result runDuckDb(Tables t, boolean withIdx) {
        return measure("DuckDB",
            (Tables x) -> {
                Class.forName("org.duckdb.DuckDBDriver", true, ClassLoader.getSystemClassLoader());
                Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS c");
                    st.execute("DROP TABLE IF EXISTS b");
                    st.execute("DROP TABLE IF EXISTS a");
                    st.execute("CREATE TABLE a(id BIGINT)");
                    st.execute("CREATE TABLE b(id BIGINT, ba DOUBLE, bb DOUBLE, bc VARCHAR, bd VARCHAR)");
                    st.execute("CREATE TABLE c(k1 DOUBLE, k2 VARCHAR, val DOUBLE)");
                }
                org.duckdb.DuckDBConnection dconn = conn.unwrap(org.duckdb.DuckDBConnection.class);
                org.duckdb.DuckDBAppender apA = dconn.createAppender("main", "a");
                for (long id : x.aId()) apA.beginRow().append(id).endRow();
                apA.close();
                org.duckdb.DuckDBAppender apB = dconn.createAppender("main", "b");
                for (int i = 0; i < x.bId().length; i++) {
                    apB.beginRow().append(x.bId()[i]).append(x.bA()[i]).append(x.bB()[i])
                       .append(x.bC()[i]).append(x.bD()[i]).endRow();
                }
                apB.close();
                org.duckdb.DuckDBAppender apC = dconn.createAppender("main", "c");
                for (int i = 0; i < x.cK1().length; i++) {
                    apC.beginRow().append(x.cK1()[i]).append(x.cK2()[i]).append(x.cV()[i]).endRow();
                }
                apC.close();
                if (withIdx) {
                    try (Statement st = conn.createStatement()) { createIndexes(st, "DuckDB"); }
                }
                return conn;
            },
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Connection conn = (Connection) ctx;
                long cnt;
                cnt = execCountQuery(conn);
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), cnt};
            },
            t);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 2: SQLite — 全 PRAGMA + 单事务 + PS.batch
    // ════════════════════════════════════════════════════════════════════════
    static Result runSqlite(Tables t, boolean withIdx) {
        return measure("SQLite",
            (Tables x) -> {
                Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader());
                Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=OFF");
                    st.execute("PRAGMA synchronous=OFF");
                    st.execute("PRAGMA temp_store=MEMORY");
                    st.execute("PRAGMA cache_size=-1000000");
                    st.execute("PRAGMA locking_mode=EXCLUSIVE");
                    st.execute("DROP TABLE IF EXISTS c");
                    st.execute("DROP TABLE IF EXISTS b");
                    st.execute("DROP TABLE IF EXISTS a");
                    st.execute("CREATE TABLE a(id INTEGER)");
                    st.execute("CREATE TABLE b(id INTEGER, ba REAL, bb REAL, bc TEXT, bd TEXT)");
                    st.execute("CREATE TABLE c(k1 REAL, k2 TEXT, val REAL)");
                }
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO a VALUES(?)")) {
                    for (int i = 0; i < x.aId().length; i++) {
                        ps.setLong(1, x.aId()[i]); ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO b VALUES(?,?,?,?,?)")) {
                    for (int i = 0; i < x.bId().length; i++) {
                        ps.setLong(1, x.bId()[i]); ps.setDouble(2, x.bA()[i]); ps.setDouble(3, x.bB()[i]);
                        ps.setString(4, x.bC()[i]); ps.setString(5, x.bD()[i]);
                        ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO c VALUES(?,?,?)")) {
                    for (int i = 0; i < x.cK1().length; i++) {
                        ps.setDouble(1, x.cK1()[i]); ps.setString(2, x.cK2()[i]); ps.setDouble(3, x.cV()[i]);
                        ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                if (withIdx) {
                    try (Statement st = conn.createStatement()) { createIndexes(st, "SQLite"); }
                }
                conn.commit();
                return conn;
            },
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Connection conn = (Connection) ctx;
                long cnt;
                cnt = execCountQuery(conn);
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), cnt};
            },
            t);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 3: H2 — in-memory + 单事务 + PS.batch(不支持表达式索引)
    // ════════════════════════════════════════════════════════════════════════
    static Result runH2(Tables t, boolean withIdx) {
        return measure("H2",
            (Tables x) -> {
                Class.forName("org.h2.Driver", true, ClassLoader.getSystemClassLoader());
                // H2 in-memory:用每次随机 DB 名避免跨测量残留;DB_CLOSE_DELAY=-1 让连接关闭即释放
                String h2Db = "bench" + System.nanoTime();
                Connection conn = DriverManager.getConnection(
                    "jdbc:h2:mem:" + h2Db + ";CACHE_SIZE=65536;DB_CLOSE_DELAY=-1");
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS c");
                    st.execute("DROP TABLE IF EXISTS b");
                    st.execute("DROP TABLE IF EXISTS a");
                    st.execute("CREATE TABLE a(id BIGINT)");
                    st.execute("CREATE TABLE b(id BIGINT, ba DOUBLE, bb DOUBLE, bc VARCHAR, bd VARCHAR)");
                    st.execute("CREATE TABLE c(k1 DOUBLE, k2 VARCHAR, val DOUBLE)");
                }
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO a VALUES(?)")) {
                    for (int i = 0; i < x.aId().length; i++) {
                        ps.setLong(1, x.aId()[i]); ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO b VALUES(?,?,?,?,?)")) {
                    for (int i = 0; i < x.bId().length; i++) {
                        ps.setLong(1, x.bId()[i]); ps.setDouble(2, x.bA()[i]); ps.setDouble(3, x.bB()[i]);
                        ps.setString(4, x.bC()[i]); ps.setString(5, x.bD()[i]);
                        ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO c VALUES(?,?,?)")) {
                    for (int i = 0; i < x.cK1().length; i++) {
                        ps.setDouble(1, x.cK1()[i]); ps.setString(2, x.cK2()[i]); ps.setDouble(3, x.cV()[i]);
                        ps.addBatch();
                        if ((i + 1) % PS_BATCH == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                if (withIdx) {
                    try (Statement st = conn.createStatement()) { createIndexes(st, "H2"); }
                }
                conn.commit();
                return conn;
            },
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Connection conn = (Connection) ctx;
                long cnt;
                cnt = execCountQuery(conn);
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), cnt};
            },
            t);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 引擎 4: jian — colAdd + assign + 多键 merge(无索引概念,两模式同结果)
    // ════════════════════════════════════════════════════════════════════════
    static Result runJian(Tables t, boolean withIdx) {
        return measure("jian",
            (Tables x) -> x,
            (ctx) -> {
                Timer tm = new Timer(); tm.start();
                Tables x = (Tables) ctx;
                Class<?> dfCls = Class.forName("jian.core.DataFrame", true, ClassLoader.getSystemClassLoader());
                var ofColumns = dfCls.getMethod("ofColumnArrays", List.class, Object[].class);
                var merge2    = dfCls.getMethod("merge", dfCls, String.class, String.class);
                var merge3    = dfCls.getMethod("merge", dfCls, String.class, String[].class, String[].class, String[].class);
                var colAdd    = dfCls.getMethod("colAdd", String.class, String.class, String.class);
                var assign    = dfCls.getMethod("assign", String.class, java.util.function.IntFunction.class);
                var rowCount  = dfCls.getMethod("rowCount");
                // 构造三表
                Object a = ofColumns.invoke(null, List.of("id"), new Object[]{x.aId()});
                Object b = ofColumns.invoke(null, List.of("id","ba","bb","bc","bd"),
                        new Object[]{x.bId(), x.bA(), x.bB(), x.bC(), x.bD()});
                Object c = ofColumns.invoke(null, List.of("k1","k2","val"),
                        new Object[]{x.cK1(), x.cK2(), x.cV()});
                // b2 = b.colAdd("sum", "ba", "bb")
                Object b2 = colAdd.invoke(b, "sum", "ba", "bb");
                // b3 = b2.assign("cat", i -> bcStr[i] + bdStr[i])
                final String[] bcStr = x.bC();
                final String[] bdStr = x.bD();
                Object b3 = assign.invoke(b2, "cat", (java.util.function.IntFunction<Object>) i ->
                        bcStr[i] + bdStr[i]);
                // ab = a.merge(b3, "inner", "id")
                Object ab = merge2.invoke(a, b3, "inner", "id");
                // abc = ab.merge(c, "inner", ["sum","cat"], ["k1","k2"], null)
                Object abc = merge3.invoke(ab, c, "inner", new String[]{"sum","cat"}, new String[]{"k1","k2"}, null);
                long cnt = (int) rowCount.invoke(abc);
                Sample s = tm.stop();
                return new long[]{s.wallMs(), s.cpuMs(), s.heapMb(), cnt};
            },
            t);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 输出 result.json
    // ────────────────────────────────────────────────────────────────────────
    static void writeJson(Map<String, Map<Long, Map<String, Result>>> all) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"seed\": ").append(SEED).append(",\n");
        sb.append("  \"sql\": \"").append(SQL.replace("\"", "\\\"")).append("\",\n");
        sb.append("  \"jvm\": \"").append(System.getProperty("java.version")).append("\",\n");
        sb.append("  \"cores\": ").append(Runtime.getRuntime().availableProcessors()).append(",\n");
        sb.append("  \"warmup\": ").append(WARMUP_RUNS).append(",\n");
        sb.append("  \"measure\": ").append(MEASURE_RUNS).append(",\n");
        sb.append("  \"modes\": {\n");
        int mi = 0;
        for (var me : all.entrySet()) {
            sb.append("    \"").append(me.getKey()).append("\": {\n");
            int si = 0;
            for (var se : me.getValue().entrySet()) {
                sb.append("      \"").append(se.getKey()).append("\": {\n");
                int ei = 0;
                for (var r : se.getValue().entrySet()) {
                    sb.append("        \"").append(r.getKey()).append("\": ");
                    if (r.getValue() == null) sb.append("null");
                    else {
                        Result v = r.getValue();
                        sb.append("{\"wall\":").append(v.wall())
                          .append(",\"cpu\":").append(v.cpu())
                          .append(",\"mem\":").append(v.mem())
                          .append(",\"count\":").append(v.count()).append("}");
                    }
                    if (++ei < se.getValue().size()) sb.append(",");
                    sb.append("\n");
                }
                sb.append("      }");
                if (++si < me.getValue().size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }");
            if (++mi < all.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  }\n}\n");
        java.nio.file.Files.writeString(
            java.nio.file.Paths.get("doc/benchmark/result.json"), sb.toString());
    }
}
