"""jian_client.py —— Python 端调用 jian jar 的客户端。

设计:subprocess 启动 java 进程,通过 stdin/stdout 用 JSON 行协议通信。
Hypothesis 生成测试输入 → 通过本客户端发给 jian → 拿回结果 → 断言性质。

为什么这样做(与 jqwik 互补):
  - Hypothesis 是 Python PBT 事实标准(NumPy/pandas/PyTorch 同款)
  - shrinking / coverage 引导能力是 MetamorphicTest 的 @RepeatedTest+Random 替代不了的
  - 与 jian-core 的 jqwik 测试形成"双语言交叉 PBT"——同一性质被两套独立实现验证
  - 避开 jqwik 1.10.x 的 Anti-AI 条款(jqwik 用 1.9.3 干净版本)
"""

from __future__ import annotations
import json
import subprocess
from pathlib import Path
from typing import Any


_HERE = Path(__file__).resolve().parent          # tests-pbt/harness/
_PROJECT_ROOT = _HERE.parent.parent               # jian/(项目根)
_JIAN_CORE_JAR = _PROJECT_ROOT / "jian" / "jian-core" / "target" / "jian-core-1.0.0.jar"
_BRIDGE_JAVA = _HERE / "JianPbtBridge.java"


class JianClient:
    """长连接客户端:启动一次 java 进程,反复一来一回。"""

    def __init__(self) -> None:
        if not _JIAN_CORE_JAR.exists():
            raise FileNotFoundError(
                f"找不到 jian-core jar:{_JIAN_CORE_JAR}\n"
                f"请先跑 `./mvnw -pl jian/jian-core install -DskipTests`"
            )
        self.proc = subprocess.Popen(
            ["java", "-cp", str(_JIAN_CORE_JAR), str(_BRIDGE_JAVA)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=1,
            text=True,
            encoding="utf-8",
        )
        resp = self._call({"op": "ping"})
        if resp.get("result") != "pong":
            raise RuntimeError(f"启动握手失败:{resp}")

    def _call(self, req: dict[str, Any]) -> dict[str, Any]:
        assert self.proc.stdin is not None
        assert self.proc.stdout is not None
        # 协议层规范化:把 NaN/Infinity 替换为 null(标准 JSON 不允许,Java 端 parseNum 不认识)
        sanitized = _sanitize_nan_inf(req)
        self.proc.stdin.write(json.dumps(sanitized, ensure_ascii=False) + "\n")
        self.proc.stdin.flush()
        line = self.proc.stdout.readline()
        if not line:
            err = self.proc.stderr.read() if self.proc.stderr else ""
            raise RuntimeError(f"java 进程退出,stderr:{err}")
        return json.loads(line)

    def close(self) -> None:
        if self.proc.poll() is None:
            if self.proc.stdin:
                self.proc.stdin.close()
            self.proc.wait(timeout=5)

    # ===== 算子封装 =====

    def sort(self, df: dict, col: str, asc: bool) -> dict:
        return self._unwrap(self._call({"op": "sort", "df": df, "args": {"col": col, "asc": asc}}))

    def filter(self, df: dict, expr: str) -> dict:
        return self._unwrap(self._call({"op": "filter", "df": df, "args": {"expr": expr}}))

    def head(self, df: dict, n: int) -> dict:
        return self._unwrap(self._call({"op": "head", "df": df, "args": {"n": n}}))

    def tail(self, df: dict, n: int) -> dict:
        return self._unwrap(self._call({"op": "tail", "df": df, "args": {"n": n}}))

    def merge(self, left: dict, right: dict, how: str, on: str) -> dict:
        return self._unwrap(self._call({"op": "merge", "dfs": [left, right], "args": {"how": how, "on": on}}))

    def groupBy(self, df: dict, by: str, col: str, fn: str) -> dict:
        return self._unwrap(self._call({"op": "groupBy", "df": df, "args": {"by": by, "col": col, "fn": fn}}))

    def concat(self, dfs: list[dict], axis: int) -> dict:
        return self._unwrap(self._call({"op": "concat", "dfs": dfs, "args": {"axis": axis}}))

    def dropDuplicates(self, df: dict, subset: list[str]) -> dict:
        return self._unwrap(self._call({"op": "dropDuplicates", "df": df, "args": {"subset": subset}}))

    def fillna(self, df: dict, value) -> dict:
        return self._unwrap(self._call({"op": "fillna", "df": df, "args": {"value": value}}))

    def dropna(self, df: dict) -> dict:
        return self._unwrap(self._call({"op": "dropna", "df": df, "args": {}}))

    def ffill(self, df: dict) -> dict:
        return self._unwrap(self._call({"op": "ffill", "df": df, "args": {}}))

    def astype(self, df: dict, col: str, target: str) -> dict:
        return self._unwrap(self._call({"op": "astype", "df": df, "args": {"col": col, "target": target}}))

    def select(self, df: dict, cols: list[str]) -> dict:
        return self._unwrap(self._call({"op": "select", "df": df, "args": {"cols": cols}}))

    def drop(self, df: dict, cols: list[str]) -> dict:
        return self._unwrap(self._call({"op": "drop", "df": df, "args": {"cols": cols}}))

    def slice(self, df: dict, a: int, b: int) -> dict:
        return self._unwrap(self._call({"op": "slice", "df": df, "args": {"a": a, "b": b}}))

    def nlargest(self, df: dict, n: int, col: str) -> dict:
        return self._unwrap(self._call({"op": "nlargest", "df": df, "args": {"n": n, "col": col}}))

    def nsmallest(self, df: dict, n: int, col: str) -> dict:
        return self._unwrap(self._call({"op": "nsmallest", "df": df, "args": {"n": n, "col": col}}))

    def colAdd(self, df: dict, newName: str, a: str, b: str) -> dict:
        return self._unwrap(self._call({"op": "colAdd", "df": df, "args": {"newName": newName, "a": a, "b": b}}))

    def colSub(self, df: dict, newName: str, a: str, b: str) -> dict:
        return self._unwrap(self._call({"op": "colSub", "df": df, "args": {"newName": newName, "a": a, "b": b}}))

    def colDiv(self, df: dict, newName: str, a: str, b: str) -> dict:
        return self._unwrap(self._call({"op": "colDiv", "df": df, "args": {"newName": newName, "a": a, "b": b}}))

    def colMulScalar(self, df: dict, newName: str, src: str, k: float) -> dict:
        return self._unwrap(self._call({"op": "colMulScalar", "df": df, "args": {"newName": newName, "src": src, "k": k}}))

    def assign(self, df: dict, newName: str, value: str) -> dict:
        return self._unwrap(self._call({"op": "assign", "df": df, "args": {"newName": newName, "value": value}}))

    # ===== 阶段 A 高频实用扩展(2026-08-09)=====

    def idxmax(self, df: dict, col: str) -> int:
        """返回列最大值首行下标;空表/全缺失返回 -1。"""
        return self._unwrap(self._call({"op": "idxmax", "df": df, "args": {"col": col}}))["idx"]

    def idxmin(self, df: dict, col: str) -> int:
        return self._unwrap(self._call({"op": "idxmin", "df": df, "args": {"col": col}}))["idx"]

    def duplicated(self, df: dict, subset: list[str] | None, keep: str = "first") -> list[bool]:
        r = self._unwrap(self._call({"op": "duplicated", "df": df,
                                      "args": {"subset": subset, "keep": keep}}))
        return list(r["mask"])

    def sample(self, df: dict, n: int, replace: bool, seed: int) -> dict:
        return self._unwrap(self._call({"op": "sample", "df": df,
                                         "args": {"n": n, "replace": replace, "seed": seed}}))

    def isin(self, df: dict, values: list) -> list[bool]:
        r = self._unwrap(self._call({"op": "isin", "df": df, "args": {"values": values}}))
        return list(r["mask"])

    def colIsin(self, df: dict, col: str, values: list) -> list[bool]:
        """列级 isin(对齐 Series.isin);返回 boolean 列表。

        实现说明:bridge 没有独立 colIsin op,用 isin + 单列 df 包装。
        关键修复:必须按 `col` 在 df["columns"] 中的索引取该列值,不能用写死的 row[1]。
        (历史 bug:之前硬编码 row[1] = 第二列 = "v",换列立即假阳。)
        """
        assert col in df["columns"], f"col={col} 未在 columns={df['columns']} 中注册"
        idx = df["columns"].index(col)
        single = {"columns": [col], "rows": [[row[idx]] for row in df["rows"]]}
        r = self._unwrap(self._call({"op": "isin", "df": single, "args": {"values": values}}))
        return list(r["mask"])

    def where(self, df: dict, cond: list[bool], other) -> dict:
        return self._unwrap(self._call({"op": "where", "df": df,
                                         "args": {"cond": cond, "other": other}}))

    def colCmp(self, df: dict, col: str, op: str, value) -> list[bool]:
        """列比较 op(对齐 DataFrame.compare(col, op, value)),返回 boolean mask。
        专用于差分测试:直接调 jian compare 拿掩码,与 pandas (df[col] op value) 对照。
        op ∈ {">", "<", ">=", "<=", "==", "!="}。"""
        r = self._unwrap(self._call({"op": "colCmp", "df": df,
                                     "args": {"col": col, "op": op, "value": value}}))
        return list(r["mask"])

    def mask(self, df: dict, cond: list[bool], other) -> dict:
        return self._unwrap(self._call({"op": "mask", "df": df,
                                         "args": {"cond": cond, "other": other}}))

    # ===== 阶段 B 统计扩展(2026-08-09)=====

    def cumsum(self, df: dict, col: str, new_col: str = None) -> dict:
        return self._unwrap(self._call({"op": "cumsum", "df": df,
                                         "args": {"col": col, "newCol": new_col}}))

    def diff(self, df: dict, col: str, periods: int = 1, new_col: str = None) -> dict:
        return self._unwrap(self._call({"op": "diff", "df": df,
                                         "args": {"col": col, "periods": periods, "newCol": new_col}}))

    def pct_change(self, df: dict, col: str, periods: int = 1, new_col: str = None) -> dict:
        return self._unwrap(self._call({"op": "pct_change", "df": df,
                                         "args": {"col": col, "periods": periods, "newCol": new_col}}))

    def clip(self, df: dict, col: str, lower: float, upper: float, new_col: str = None) -> dict:
        return self._unwrap(self._call({"op": "clip", "df": df,
                                         "args": {"col": col, "lower": lower, "upper": upper, "newCol": new_col}}))

    def quantile(self, df: dict, col: str, q: float) -> float:
        r = self._unwrap(self._call({"op": "quantile", "df": df, "args": {"col": col, "q": q}}))
        return float(r["value"])

    def rank(self, df: dict, col: str, method: str = "average", new_col: str = None) -> dict:
        return self._unwrap(self._call({"op": "rank", "df": df,
                                         "args": {"col": col, "method": method, "newCol": new_col}}))

    def round(self, df: dict, col: str, decimals: int = 0, new_col: str = None) -> dict:
        return self._unwrap(self._call({"op": "round", "df": df,
                                         "args": {"col": col, "decimals": decimals, "newCol": new_col}}))

    def prod(self, df: dict, col: str) -> float:
        r = self._unwrap(self._call({"op": "prod", "df": df, "args": {"col": col}}))
        return float(r["value"])

    # ===== 阶段 C 重塑合并扩展(2026-08-09)=====

    def pivot(self, df: dict, index: str, columns: str, values: str) -> dict:
        return self._unwrap(self._call({"op": "pivot", "df": df,
                                         "args": {"index": index, "columns": columns, "values": values}}))

    def explode(self, df: dict, col: str) -> dict:
        return self._unwrap(self._call({"op": "explode", "df": df, "args": {"col": col}}))

    def mergeAsof(self, left: dict, right: dict, on: str) -> dict:
        return self._unwrap(self._call({"op": "mergeAsof", "dfs": [left, right], "args": {"on": on}}))

    @staticmethod
    def _unwrap(resp: dict) -> dict:
        if resp.get("ok") is not True:
            raise AssertionError(f"jian 调用失败:{resp.get('error')}")
        return resp["result"]


# === 模块级单例(测试复用,避免每个测试启动一次 java)===
_client: JianClient | None = None


def get_client() -> JianClient:
    global _client
    if _client is None:
        _client = JianClient()
    return _client


def close_client() -> None:
    global _client
    if _client is not None:
        _client.close()
        _client = None


# === DataFrame 构造辅助(供 Hypothesis 测试用)===

def make_df(columns: list[str], rows: list[list[Any]]) -> dict:
    """按 jian 的 JSON 协议构造 df。"""
    return {"columns": columns, "rows": rows}


def _sanitize_nan_inf(obj: Any) -> Any:
    """递归把 NaN/Infinity 替换为 null(标准 JSON 不允许 NaN/Infinity,
    Python json.dumps 默认输出但 Java 端 parseNum 不认识)。

    协议层规范化,符合 RFC 8259。NaN 在 jian 语义里就是"缺失",null 是等价表达。
    """
    import math
    if isinstance(obj, float):
        if math.isnan(obj) or math.isinf(obj):
            return None
        return obj
    if isinstance(obj, dict):
        return {k: _sanitize_nan_inf(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_sanitize_nan_inf(v) for v in obj]
    return obj
