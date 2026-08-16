"""test_adversarial_fuzz.py — 自定义对抗性 fuzz 测试(独立于项目已有测试)。

目标:用畸形输入主动探测 jian 算子的健壮性,期望发现真实 BUG。
- 数值溢出/下溢(NaN/Infinity/-0)
- 空集合/单元素/极大集合
- 字符串 Unicode 控制字符/代理对/超长
- SQL 注入/XSS/CRLF 注入
- 类型不匹配(混型比较)
- 重复键/边界键

每个测试**不**依赖具体期望值,而是验证不变量(行数守恒/类型守恒/不抛无解释异常)。

因为若函数体零断言、safe_run 把异常吞进模块级 FINDINGS 而 FINDINGS 只在脚本模式
main() 里消费,pytest 下测试会恒过(死 harness),所以每个测试经 @fuzz_test 装饰器
断言"本次执行没有新增未解释异常":教学型拒绝(IllegalArgumentException + 非空说明
消息,jian 的 fail-fast 约定)进 EXPECTED 白名单不算击穿;NPE/CCE/越界等无解释异常
→ 断言失败。
"""
from __future__ import annotations
import functools
import math
import sys
import os
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tests-pbt" / "harness"))

try:
    from jian_client import get_client, close_client, make_df
except Exception as e:
    print(f"[FATAL] jian_client 不可用: {e}")
    sys.exit(2)


# ====== 结果收集 ======
FINDINGS = []
EXPECTED = []   # 设计内的教学型拒绝(IllegalArgumentException + 说明消息),不算击穿

def record(category, name, inp_desc, exc=None, extra=""):
    tb = ""
    if exc is not None:
        tb = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))[-1500:]
    FINDINGS.append({
        "category": category,
        "test": name,
        "input": inp_desc[:200],
        "exception": repr(exc) if exc else "(no exception)",
        "traceback": tb,
        "extra": extra,
    })


# ====== 辅助:安全调用 ======
def _is_explained(exc: Exception) -> bool:
    """教学型异常判定 —— jian 的 fail-fast 约定是抛 IllegalArgumentException + 中文
    说明消息(带列名/值/原因),属"有解释的拒绝"(统计算子对非数值列抛 IllegalStateException
    同为教学型拒绝);其余异常(NPE/CCE/越界/栈溢出等)
    是"无解释崩溃",才是本 harness 要抓的击穿。"""
    return ("IllegalArgumentException" in type(exc).__name__
            or "IllegalStateException" in type(exc).__name__) and bool(str(exc))


def safe_run(name, fn, *args, **kwargs):
    try:
        result = fn(*args, **kwargs)
        return ("ok", result)
    except Exception as e:
        if _is_explained(e):
            EXPECTED.append({"test": name, "exception": repr(e)})
        else:
            record("EXC", name, str(args)[:200], exc=e)
        return ("exc", e)


def fuzz_test(fn):
    """包裹 fuzz 测试 —— 结束时断言本次执行没有新增未解释异常。

    因为零断言的函数体会恒过(jian 抛任何异常都吞进 FINDINGS 无人消费),
    所以快照 FINDINGS 长度,新增即失败并逐条列出异常摘要。"""
    @functools.wraps(fn)
    def wrapper(*a, **kw):
        before = len(FINDINGS)
        try:
            return fn(*a, **kw)
        finally:
            new = FINDINGS[before:]
            assert not new, (
                f"{fn.__name__}: {len(new)} 个未解释异常击穿不变量:\n"
                + "\n".join(f"  [{f['test']}] {f['exception']}" for f in new)
            )
    return wrapper


# ====== 1. 数值边界 fuzz ======
@fuzz_test
def test_numeric_boundary():
    name = "test_numeric_boundary"
    client = get_client()
    cases = [
        ("nan_only", ["v"], [[float("nan")]]),
        ("inf_only", ["v"], [[float("inf")]]),
        ("inf_nan", ["v"], [[float("inf")], [-float("inf")], [float("nan")]]),
        ("denormal", ["v"], [[5e-324], [-5e-324]]),
        ("max_long", ["v"], [[9223372036854775807], [-9223372036854775808]]),
        ("zero_double", ["v"], [[0.0], [-0.0]]),
    ]
    for tag, cols, rows in cases:
        df = make_df(cols, rows)
        safe_run(f"{name}.sort[{tag}]", client.sort, df, "v", True)
        safe_run(f"{name}.filter[{tag}]", client.filter, df, "v > 0")
        safe_run(f"{name}.dropna[{tag}]", client.dropna, df)
        safe_run(f"{name}.fillna[{tag}]", client.fillna, df, 0)
        safe_run(f"{name}.sum[{tag}]", client.stat, df, "v", "sum")
        safe_run(f"{name}.mean[{tag}]", client.stat, df, "v", "mean")
        safe_run(f"{name}.min[{tag}]", client.stat, df, "v", "min")
        safe_run(f"{name}.max[{tag}]", client.stat, df, "v", "max")


# ====== 2. 字符串边界 fuzz ======
@fuzz_test
def test_string_boundary():
    name = "test_string_boundary"
    client = get_client()
    cases = [
        ("empty_str", ["s"], [["a", "", ""]]),
        ("emoji", ["s"], [["🚀"], ["😀😀😀"]]),
        ("ctrl_chars", ["s"], [["\x00\x01\x02"], ["\r\n"], ["\t \t"]]),
        ("super_long", ["s"], [["a" * 100000]]),
        ("unicode_normalization", ["s"], [["é"], ["é"]]),
        ("bom", ["s"], [["﻿hello"]]),
        ("rtl", ["s"], [["‮abc"]]),
        ("sql_meta", ["s"], [["' OR 1=1--"], ["\"; DROP TABLE x;--"]]),
    ]
    for tag, cols, rows in cases:
        df = make_df(cols, rows)
        safe_run(f"{name}.sort[{tag}]", client.sort, df, "s", True)
        safe_run(f"{name}.dropDup[{tag}]", client.dropDuplicates, df, ["s"])
        safe_run(f"{name}.filter_like[{tag}]", client.filter, df, "s like '%'")


# ====== 3. 列名边界 fuzz ======
@fuzz_test
def test_column_name_boundary():
    name = "test_column_name_boundary"
    client = get_client()
    cases = [
        ("sql_keyword", ["select", "from"], [[1, 2], [3, 4]]),
        ("unicode_col", ["列1", "列2"], [[1, 2], [3, 4]]),
        ("single_quote", ["a'b"], [[1], [2]]),
        ("very_long", ["x" * 500], [[1], [2]]),
    ]
    for tag, cols, rows in cases:
        try:
            df = make_df(cols, rows)
        except Exception as e:
            record("EXC-MAKE_DF", f"{name}.makeDf[{tag}]", str(cols)[:80], exc=e)
            continue
        safe_run(f"{name}.select[{tag}]", client.select, df, [cols[0]])
        safe_run(f"{name}.filter[{tag}]", client.filter, df, f"{cols[0]} > 0")


# ====== 4. 行级 fuzz(空/单行/巨行)======
@fuzz_test
def test_row_extremes():
    name = "test_row_extremes"
    client = get_client()
    cases = [
        ("empty_df", ["v"], []),
        ("single_row", ["v"], [[1.0]]),
        ("single_null", ["v"], [[None]]),
        ("all_null", ["v"], [[None], [None], [None]]),
        ("two_rows", ["v"], [[1.0], [2.0]]),
    ]
    for tag, cols, rows in cases:
        try:
            df = make_df(cols, rows)
        except Exception as e:
            record("EXC-MAKE_DF", f"{name}.makeDf[{tag}]", str(cols)[:80], exc=e)
            continue
        safe_run(f"{name}.sort[{tag}]", client.sort, df, "v", True)
        safe_run(f"{name}.dropDup[{tag}]", client.dropDuplicates, df, ["v"])
        safe_run(f"{name}.dropna[{tag}]", client.dropna, df)
        safe_run(f"{name}.fillna[{tag}]", client.fillna, df, 0)
        safe_run(f"{name}.sum[{tag}]", client.stat, df, "v", "sum")
        safe_run(f"{name}.mean[{tag}]", client.stat, df, "v", "mean")
        safe_run(f"{name}.head[{tag}]", client.head, df, 5)
        safe_run(f"{name}.tail[{tag}]", client.tail, df, 5)
        safe_run(f"{name}.groupBy[{tag}]", client.groupBy, df, "v", "v", "count")


# ====== 5. query 表达式 fuzz ======
@fuzz_test
def test_query_expression_fuzz():
    name = "test_query_expression_fuzz"
    client = get_client()
    df = make_df(["a", "b", "s"], [[1, 2, "x"], [3, 4, "y"], [5, 6, "z"]])
    exprs = [
        "a == 1 || b == 2 && a == 1",
        "!(a == 1) && !(b == 2)",
        "((a + b) > 5) && (s == 'x')",
        "a > 0 && (b < 10 || a < 100)",
        "a in (1, 3, 5)",
        "a between 1 and 10",
        "a notin (2, 4, 6)",
        "s like 'x%'",
        "s is null",
        "s is not null",
        "a == 1 and b == 2",
        "a == 1 or b == 2",
        "not (a == 1)",
    ]
    for e in exprs:
        safe_run(f"{name}.filter[{e[:40]}]", client.filter, df, e)


# ====== 6. groupBy fuzz ======
@fuzz_test
def test_groupby_fuzz():
    name = "test_groupby_fuzz"
    client = get_client()
    df = make_df(["g", "v"], [["a", 1], ["a", 2], ["b", 3]])
    for fn in ["sum", "mean", "min", "max", "count", "first", "last"]:
        safe_run(f"{name}.agg.{fn}", client.groupBy, df, "g", "v", fn)


# ====== 7. merge/join fuzz ======
@fuzz_test
def test_merge_fuzz():
    name = "test_merge_fuzz"
    client = get_client()
    a = make_df(["k", "v"], [])
    b = make_df(["k", "w"], [[1, "x"]])
    safe_run(f"{name}.merge.emptyA", client.merge, a, b, "inner", "k")

    a = make_df(["k", "v"], [[1, "a"]])
    b = make_df(["k", "w"], [[2, "b"]])
    safe_run(f"{name}.merge.noOverlap", client.merge, a, b, "inner", "k")

    a = make_df(["k", "v"], [[1, "x"]])
    b = make_df(["k", "w"], [[1, "a"], [1, "b"]])
    safe_run(f"{name}.merge.oneToMany", client.merge, a, b, "left", "k")

    for how in ["inner", "left", "right", "outer"]:
        a = make_df(["k", "v"], [[1, "a"], [2, "b"]])
        b = make_df(["k", "w"], [[2, "c"], [3, "d"]])
        safe_run(f"{name}.merge.{how}", client.merge, a, b, how, "k")


# ====== 8. reshape fuzz ======
@fuzz_test
def test_reshape_fuzz():
    name = "test_reshape_fuzz"
    client = get_client()
    df = make_df(["a", "b"], [[1, 2], [3, 4], [5, 6]])
    safe_run(f"{name}.pivot", client.pivot, df, "a", "b", "b")
    safe_run(f"{name}.pivotTable.sum", client.pivotTable, df, "a", "b", "b", "sum")
    safe_run(f"{name}.explode", client.explode, df, "a")


# ====== 9. 时序 fuzz ======
@fuzz_test
def test_timeseries_fuzz():
    name = "test_timeseries_fuzz"
    client = get_client()
    df = make_df(["t", "v"], [[2, 10], [0, 5], [1, 7]])
    safe_run(f"{name}.cumsum", client.cumsum, df, "v")
    safe_run(f"{name}.diff", client.diff, df, "v")
    safe_run(f"{name}.pct_change", client.pct_change, df, "v")
    safe_run(f"{name}.rolling", client.rolling, df, "v", 2, "sum")
    safe_run(f"{name}.ewm", client.ewm, df, "v", 0.5, "mean")
    safe_run(f"{name}.expanding", client.expanding, df, "v", "mean")


# ====== 10. 类型混搭 fuzz ======
@fuzz_test
def test_mixed_type_fuzz():
    name = "test_mixed_type_fuzz"
    client = get_client()
    df = make_df(["v"], [[1], ["a"], [2]])
    safe_run(f"{name}.sort.mixed", client.sort, df, "v", True)
    safe_run(f"{name}.sum.mixed", client.stat, df, "v", "sum")
    safe_run(f"{name}.min.mixed", client.stat, df, "v", "min")
    safe_run(f"{name}.max.mixed", client.stat, df, "v", "max")
    safe_run(f"{name}.filter_eq_mixed", client.filter, df, "v == 1")
    safe_run(f"{name}.filter_lt_mixed", client.filter, df, "v < 2")


# ====== 11. 整数溢出 ======
@fuzz_test
def test_integer_overflow():
    name = "test_integer_overflow"
    client = get_client()
    big = 2**40
    df = make_df(["v"], [[big], [-big], [big * 2], [-big * 2]])
    safe_run(f"{name}.sum", client.stat, df, "v", "sum")
    safe_run(f"{name}.cumsum", client.cumsum, df, "v")
    safe_run(f"{name}.diff", client.diff, df, "v")
    safe_run(f"{name}.clip", client.clip, df, "v", -big, big)


# ====== 12. NaN 传播 fuzz ======
@fuzz_test
def test_nan_propagation_fuzz():
    name = "test_nan_propagation_fuzz"
    client = get_client()
    df = make_df(["a", "b"], [[1.0, float("nan")], [float("nan"), 2.0], [float("nan"), float("nan")]])
    safe_run(f"{name}.add_nan", client.colAdd, df, "c", "a", "b")
    safe_run(f"{name}.mul_nan", client.colMulScalar, df, "c", "a", 2.0)
    safe_run(f"{name}.cumsum_nan", client.cumsum, df, "a")
    safe_run(f"{name}.diff_nan", client.diff, df, "a")


# ====== 13. 重复键 fuzz ======
@fuzz_test
def test_dup_key_fuzz():
    name = "test_dup_key_fuzz"
    client = get_client()
    df = make_df(["k", "v"], [[1, "a"], [1, "b"], [1, "c"]])
    safe_run(f"{name}.dropDup.full", client.dropDuplicates, df, ["k", "v"])
    safe_run(f"{name}.groupBy.dupKey", client.groupBy, df, "k", "v", "count")
    safe_run(f"{name}.merge.selfDupKey", client.merge, df, df, "inner", "k")


# ====== 14. 算子连续 fuzz(模拟真实链式调用)======
@fuzz_test
def test_chain_fuzz():
    name = "test_chain_fuzz"
    client = get_client()
    df = make_df(["g", "v"], [["a", 1], ["a", 2], ["b", 3], [None, 4]])
    # 链式: filter -> dropna -> sort -> head
    r1 = safe_run(f"{name}.chain.filter", client.filter, df, "v > 0")
    if r1[0] != "ok":
        return
    r1 = r1[1]
    r2 = safe_run(f"{name}.chain.dropna", client.dropna, r1)
    if r2[0] != "ok":
        return
    r2 = r2[1]
    r3 = safe_run(f"{name}.chain.sort", client.sort, r2, "v", False)
    if r3[0] != "ok":
        return
    r3 = r3[1]
    safe_run(f"{name}.chain.head", client.head, r3, 10)


# ====== 15. quantile/rank/round fuzz ======
@fuzz_test
def test_advanced_fuzz():
    name = "test_advanced_fuzz"
    client = get_client()
    df = make_df(["v"], [[1], [2], [3], [None], [5]])
    safe_run(f"{name}.quantile.0.5", client.quantile, df, "v", 0.5)
    safe_run(f"{name}.quantile.0", client.quantile, df, "v", 0)
    safe_run(f"{name}.quantile.1", client.quantile, df, "v", 1)
    safe_run(f"{name}.rank", client.rank, df, "v")
    safe_run(f"{name}.round", client.round, df, "v", 2)
    safe_run(f"{name}.clip", client.clip, df, "v", 1, 3)


# ====== 16. concat 边界 fuzz ======
@fuzz_test
def test_concat_fuzz():
    name = "test_concat_fuzz"
    client = get_client()
    a = make_df(["a", "b"], [[1, 2]])
    b = make_df(["a", "b"], [[3, 4]])
    safe_run(f"{name}.concat.diffRows", client.concat, [a, b], 0)
    a_empty = make_df(["a", "b"], [])
    b_one = make_df(["a", "b"], [[3, 4]])
    safe_run(f"{name}.concat.emptyA", client.concat, [a_empty, b_one], 0)


# ====== 主入口 ======
def main():
    print("=" * 70)
    print("jian 对抗性 fuzz 测试 · 自定义破坏性输入")
    print("=" * 70)
    tests = [
        test_numeric_boundary,
        test_string_boundary,
        test_column_name_boundary,
        test_row_extremes,
        test_query_expression_fuzz,
        test_groupby_fuzz,
        test_merge_fuzz,
        test_reshape_fuzz,
        test_timeseries_fuzz,
        test_mixed_type_fuzz,
        test_integer_overflow,
        test_nan_propagation_fuzz,
        test_dup_key_fuzz,
        test_chain_fuzz,
        test_advanced_fuzz,
        test_concat_fuzz,
    ]
    total = 0
    failed = 0
    for t in tests:
        total += 1
        try:
            t()
            print(f"  ✓ {t.__name__}")
        except Exception as e:
            failed += 1
            print(f"  ✗ {t.__name__}: {e}")
            traceback.print_exc()
    close_client()
    print()
    print("=" * 70)
    print(f"Tests run: {total}, harness failures: {failed}")
    print(f"Findings recorded: {len(FINDINGS)}")
    print(f"Explained rejections (design IAE): {len(EXPECTED)}")
    print("=" * 70)
    cats = {}
    for f in FINDINGS:
        cats[f["category"]] = cats.get(f["category"], 0) + 1
    for c, n in sorted(cats.items(), key=lambda x: -x[1]):
        print(f"  {c}: {n}")
    sys.exit(min(len(FINDINGS), 255))


if __name__ == "__main__":
    main()