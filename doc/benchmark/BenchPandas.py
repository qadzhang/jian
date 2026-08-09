#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pandas 复合关联 benchmark(对齐 JoinBenchmark.java 的 SQL)
================================================================
What : 用 pandas 跑与 JoinBenchmark.java 相同模式的复合关联,记录 wall 时间。
Why  : 在「jian vs DuckDB/SQLite/H2」对比表里增加 pandas 一列,展示 pandas 在
       复合关联场景的相对位置(pandas 是 C 扩展 + numpy,常比 JVM 引擎快)。
Where: doc/benchmark/BenchPandas.py
How  :
  ① numpy 固定种子生成 a/b/c 三表(规模、分布与 Java 端一致,数据本身不必逐行相同):
       a: id  ∈ [0, 2N) 均匀随机
       b: id  ∈ [0, 2N) 随机; ba,bb ∈ [0,1000) 整数; bc="p"+i%100, bd="_"+i%50
       c: 取 b 前 80% 派生 → k1=ba+bb, k2=bc+bd (保证 80% 匹配率)
  ② pandas 等价 SQL:
       SELECT count(*) FROM a JOIN b ON a.id=b.id
                JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc+b.d)=c.k2
     实现:
       ab = a.merge(b, on="id")
       ab["sum"] = ab["ba"] + ab["bb"]
       ab["cat"] = ab["bc"] + ab["bd"]
       abc = ab.merge(c, left_on=["sum","cat"], right_on=["k1","k2"])
       cnt = len(abc)
  ③ 每规模预热 1 + 测量 3 取中位数,wall 时间(ms)

运行:
  cd <仓库根目录>
  python3 doc/benchmark/BenchPandas.py [N1 N2 ...]
  默认 N = 100000 500000 5000000
  输出: 控制台 + doc/benchmark/result_pandas_join.json
"""
import sys, json, time, statistics
import numpy as np
import pandas as pd

SEED = 20260808
WARMUP, MEASURE = 1, 3

def gen_tables(n):
    """生成 a/b/c 三表(规模/分布对齐 Java 端,数据本身不必逐行相同)。"""
    rng = np.random.default_rng(SEED ^ n)
    # a
    a = pd.DataFrame({"id": rng.integers(0, 2*n, size=n, dtype=np.int64)})
    # b
    b = pd.DataFrame({
        "id": rng.integers(0, 2*n, size=n, dtype=np.int64),
        "ba": rng.integers(0, 1000, size=n, dtype=np.int64),
        "bb": rng.integers(0, 1000, size=n, dtype=np.int64),
        "bc": np.array(["p" + str(i % 100) for i in range(n)], dtype=object),
        "bd": np.array(["_" + str(i % 50) for i in range(n)], dtype=object),
    })
    # c: 取 b 前 80% 派生 → k1=ba+bb, k2=bc+bd
    cn = int(n * 8 / 10)
    c = pd.DataFrame({
        "k1": (b["ba"].iloc[:cn].values + b["bb"].iloc[:cn].values).astype(np.int64),
        "k2": (b["bc"].iloc[:cn].values + b["bd"].iloc[:cn].values),
        "val": rng.random(cn),
    })
    return a, b, c

def run_once(a, b, c):
    """pandas 等价 SQL: a JOIN b ON a.id=b.id JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc+b.d)=c.k2。"""
    ab = a.merge(b, on="id")
    # 复合关联键物化(数字求和 + 字符串拼接)
    ab["sum"] = ab["ba"] + ab["bb"]
    ab["cat"] = ab["bc"] + ab["bd"]
    # 多键关联
    abc = ab.merge(c, left_on=["sum", "cat"], right_on=["k1", "k2"])
    return len(abc)

def measure(n):
    a, b, c = gen_tables(n)
    # 预热
    for _ in range(WARMUP):
        cnt = run_once(a, b, c)
    # 测量
    walls, cnts = [], []
    for _ in range(MEASURE):
        t0 = time.perf_counter()
        cnt = run_once(a, b, c)
        walls.append((time.perf_counter() - t0) * 1000)
        cnts.append(cnt)
    return int(statistics.median(walls)), cnts[0]

def main():
    sizes = [int(x) for x in sys.argv[1:]] or [100_000, 500_000, 5_000_000]
    print("=" * 88)
    print("pandas 复合关联 benchmark(对齐 JoinBenchmark.java)")
    print("=" * 88)
    print(f"pandas {pd.__version__} | numpy {np.__version__} | 种子 {SEED} | 预热 {WARMUP} + 测量 {MEASURE}(中位数)")
    print(f"SQL: SELECT count(*) FROM a JOIN b ON a.id=b.id")
    print(f"                 JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc||b.d)=c.k2")
    print("-" * 88)
    results = {}
    for n in sizes:
        try:
            wall, cnt = measure(n)
            print(f"  N = {n:>10,}  wall = {wall:>8,} ms  count = {cnt:,}")
            results[str(n)] = {"wall": wall, "count": int(cnt)}
        except Exception as e:
            print(f"  N = {n:>10,}  失败: {e}")
            results[str(n)] = {"wall": -1, "count": -1, "error": str(e)}
    out = {
        "engine": "pandas",
        "pandas_version": pd.__version__,
        "numpy_version": np.__version__,
        "seed": SEED,
        "sql": "SELECT count(*) FROM a JOIN b ON a.id=b.id JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc||b.d)=c.k2",
        "sizes": results,
    }
    with open("doc/benchmark/result_pandas_join.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\n→ 结果已写入 doc/benchmark/result_pandas_join.json")

if __name__ == "__main__":
    main()
