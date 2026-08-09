#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pandas 单表聚合 benchmark(对齐 SingleTableBenchmark.java 的 SQL)
================================================================
What : 用 pandas 跑与 SingleTableBenchmark.java 相同模式的单表 WHERE+GROUP BY。
Why  : 在「单表聚合」对比表里增加 pandas 一列,展示 pandas 的相对位置。
Where: doc/benchmark/BenchPandasSingle.py
How  :
  ① numpy 固定种子生成 t(N 行): id ∈ [0,2N), k ∈ [0,100), v ∈ [0,1)
  ② pandas 等价 SQL:
       SELECT k, count(*), sum(v) FROM t WHERE id % 10 = 0 GROUP BY k
     实现:
       tf = t[t["id"] % 10 == 0]
       agg = tf.groupby("k").agg(cnt=("id","size"), s=("v","sum")).reset_index()
       groups = len(agg)
  ③ 每规模预热 1 + 测量 3 取中位数,wall 时间(ms)

运行:
  cd <仓库根目录>
  python3 doc/benchmark/BenchPandasSingle.py [N1 N2 ...]
  默认 N = 1000000 5000000 10000000
  输出: 控制台 + doc/benchmark/result_pandas_single.json
"""
import sys, json, time, statistics
import numpy as np
import pandas as pd

SEED = 20260808
WARMUP, MEASURE = 1, 3

def gen_table(n):
    rng = np.random.default_rng(SEED ^ n)
    return pd.DataFrame({
        "id": rng.integers(0, 2*n, size=n, dtype=np.int64),
        "k":  rng.integers(0, 100, size=n, dtype=np.int64),
        "v":  rng.random(n),
    })

def run_once(t):
    """pandas 等价 SQL: SELECT k, count(*), sum(v) WHERE id%10=0 GROUP BY k。"""
    tf = t[t["id"] % 10 == 0]
    agg = tf.groupby("k", as_index=False).agg(cnt=("id", "size"), s=("v", "sum"))
    return len(agg)

def measure(n):
    t = gen_table(n)
    for _ in range(WARMUP):
        groups = run_once(t)
    walls, groups_list = [], []
    for _ in range(MEASURE):
        t0 = time.perf_counter()
        groups = run_once(t)
        walls.append((time.perf_counter() - t0) * 1000)
        groups_list.append(groups)
    return int(statistics.median(walls)), groups_list[0]

def main():
    sizes = [int(x) for x in sys.argv[1:]] or [1_000_000, 5_000_000, 10_000_000]
    print("=" * 88)
    print("pandas 单表聚合 benchmark(对齐 SingleTableBenchmark.java)")
    print("=" * 88)
    print(f"pandas {pd.__version__} | numpy {np.__version__} | 种子 {SEED} | 预热 {WARMUP} + 测量 {MEASURE}(中位数)")
    print(f"SQL: SELECT k, count(*), sum(v) FROM t WHERE id % 10 = 0 GROUP BY k")
    print("-" * 88)
    results = {}
    for n in sizes:
        try:
            wall, groups = measure(n)
            print(f"  N = {n:>10,}  wall = {wall:>8,} ms  组数 = {groups}")
            results[str(n)] = {"wall": wall, "groups": int(groups)}
        except Exception as e:
            print(f"  N = {n:>10,}  失败: {e}")
            results[str(n)] = {"wall": -1, "groups": -1, "error": str(e)}
    out = {
        "engine": "pandas",
        "pandas_version": pd.__version__,
        "numpy_version": np.__version__,
        "seed": SEED,
        "sql": "SELECT k, count(*), sum(v) FROM t WHERE id % 10 = 0 GROUP BY k",
        "sizes": results,
    }
    with open("doc/benchmark/result_pandas_single.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\n→ 结果已写入 doc/benchmark/result_pandas_single.json")

if __name__ == "__main__":
    main()
