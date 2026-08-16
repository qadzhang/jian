"""jian_client.py —— Python 端 JPype 直调 jian-core(替代已废弃的 subprocess+JSON 方案)。

设计:JVM 嵌入 Python 进程(jpype.startJVM),JClass("JianJpypeBridge") 直接调 Java 静态方法,
被测 jar(jian-core)只需在 classpath,不需要桥支持任何"协议"——这是 ai-code-testing skill
的标准做法(模板 java-py-bridge-template.py),对任意 jar 通用。

与旧版(subprocess + JSON 行协议)的差异:
  - 旧:每个请求 `Popen(java ...)` + 手写 JSON 解析/序列化(874 行 bridge)
  - 新:JVM 常驻进程内,数据经 java.util.ArrayList 直传,返回经 _to_py() 归一为 Python 原生
  - 对外 API 完全不变:get_client()/close_client()/make_df()/client.xxx(...) 签名与返回值结构兼容,
    旧协议把 NaN/Infinity 序列化为 null(None);新协议返回 Python float nan —— 测试里的
    `jv is None or (isinstance(jv, float) and math.isnan(jv))` 双判断两者都兼容。
"""

from __future__ import annotations
import math
import sys
from pathlib import Path
from typing import Any

import jpype
import jpype.imports  # noqa: F401  启用 JClass / JArray 类型导入

_HERE = Path(__file__).resolve().parent          # tests-pbt/harness/
_PROJECT_ROOT = _HERE.parent.parent               # jian/(项目根)
_JIAN_CORE_JAR = _PROJECT_ROOT / "jian" / "jian-core" / "target" / "jian-core-1.0.1.jar"
_JIAN_DSL_JAR = _PROJECT_ROOT / "jian" / "jian-dsl" / "target" / "jian-dsl-1.0.1.jar"
_CLASSES_DIR = _HERE.parent / "classes"           # tests-pbt/classes(JianJpypeBridge 编译产物)
_BRIDGE_CLASS = "JianJpypeBridge"


def _ensure_jvm() -> None:
    """启动 JVM(仅一次)。JVM 路径由 jpype 从 JAVA_HOME 自动定位(零本机绑定,见 AGENTS.md §0.2)。"""
    if not jpype.isJVMStarted():
        ensure_built()
        # 因为 pandas 对照测试要覆盖用户主路径(SPI 自动升级 PrattEngine),
        # 所以 classpath 加 jian-dsl;缺失时警告并回落 core 兜底(SimpleQueryParser)。
        _cp = [str(_JIAN_CORE_JAR)]
        if _JIAN_DSL_JAR.exists():
            _cp.append(str(_JIAN_DSL_JAR))
        else:
            print(f"[jian_client] 警告:未找到 {_JIAN_DSL_JAR},对照测试将走 core 兜底引擎"
                  f"(SimpleQueryParser)而非主路径(PrattEngine);建议 ./mvnw -pl jian/jian-dsl install -DskipTests",
                  file=sys.stderr)
        _cp.append(str(_CLASSES_DIR))
        jpype.startJVM(classpath=_cp)


def ensure_built() -> None:
    """构建产物存在性检查 —— jar/桥缺失时抛 FileNotFoundError。

    Why:测试文件头部的 `except FileNotFoundError: pytest.skip(allow_module_level=True)`
    守卫只在 import 期触发,若检查推迟到 _ensure_jvm()(测试体内),except 分支永远
    不执行,jar 未构建时测试会逐个 error 而非整套件 skip。因此测试文件在 import 后
    显式调用本方法,让守卫真正生效。
    """
    if not _JIAN_CORE_JAR.exists():
        raise FileNotFoundError(
            f"找不到 jian-core jar:{_JIAN_CORE_JAR}\n"
            f"请先跑 `./mvnw -pl jian/jian-core install -DskipTests`"
        )
    if not (_CLASSES_DIR / "JianJpypeBridge.class").exists():
        raise FileNotFoundError(
            f"找不到编译后的桥:{_CLASSES_DIR / 'JianJpypeBridge.class'}\n"
            f"请先跑 `javac -cp <jian-core.jar> -d tests-pbt/classes tests-pbt/harness/JianJpypeBridge.java`"
        )


_ArrayList = None  # 惰性缓存 JClass("java.util.ArrayList")


def _array_list() -> Any:
    global _ArrayList
    if _ArrayList is None:
        _ArrayList = jpype.JClass("java.util.ArrayList")
    return _ArrayList


# === Python 原生 → Java 结构 ===

def _to_java_df(df: dict) -> tuple[Any, Any]:
    """Python df({"columns":[...],"rows":[...]})→ (ArrayList<String>, ArrayList<ArrayList<Object>>)。"""
    AL = _array_list()
    cols = AL()
    for c in df["columns"]:
        cols.add(c)
    rows = AL()
    for r in df["rows"]:
        jr = AL()
        for v in r:
            jr.add(v)   # None → null;str/int/float/bool 由 JPype 自动装箱
        rows.add(jr)
    return cols, rows


def _to_java_string_list(py_list: list | None) -> Any:
    """Python list[str] → ArrayList<String>(None 原样传 None,Java 端判 null)。"""
    if py_list is None:
        return None
    AL = _array_list()
    jl = AL()
    for s in py_list:
        jl.add(s)
    return jl


def _to_java_bool_list(py_list: list[bool]) -> Any:
    AL = _array_list()
    jl = AL()
    for b in py_list:
        jl.add(bool(b))
    return jl


def _to_java_object_list(py_list: list) -> Any:
    AL = _array_list()
    jl = AL()
    for v in py_list:
        jl.add(v)
    return jl


# === Java 结构 → Python 原生 ===

# 惰性缓存 JClass(零硬编码:java.* 全限名稳定,不依赖 JPype 内部类名)
_JMap = _JList = _JString = _JNumber = _JDouble = _JFloat = _JBoolean = None
_JLocalDateTime = _JLocalDate = None


def _jcls(name: str) -> Any:
    return jpype.JClass(name)


def _to_py(obj: Any) -> Any:
    """Java 对象递归转 Python 原生:Map→dict、List→list、String→str、Number→int/float、
    java.lang.Boolean→bool、java.time.LocalDateTime/LocalDate→ISO 字符串(时间类型若不
    显式转 ISO,会原样返回 Java 包装对象,时间列的 pandas 对照无法进行)、
    数组(boolean[]/long[]/double[]/Object[])→list、null→None。
    用 isinstance + java.* 接口判断(JPype 包装类名随版本变动,硬编码类名不可靠)。"""
    global _JMap, _JList, _JString, _JNumber, _JDouble, _JFloat, _JBoolean, _JLocalDateTime, _JLocalDate
    if obj is None:
        return None
    if _JMap is None:
        _JMap = _jcls("java.util.Map")
        _JList = _jcls("java.util.List")
        _JString = _jcls("java.lang.String")
        _JNumber = _jcls("java.lang.Number")
        _JDouble = _jcls("java.lang.Double")
        _JFloat = _jcls("java.lang.Float")
        _JBoolean = _jcls("java.lang.Boolean")
        _JLocalDateTime = _jcls("java.time.LocalDateTime")
        _JLocalDate = _jcls("java.time.LocalDate")
    if isinstance(obj, _JString):
        return str(obj)
    if isinstance(obj, _JMap):
        return {str(k): _to_py(obj.get(k)) for k in obj.keySet()}
    if isinstance(obj, _JBoolean):
        return bool(obj)
    if isinstance(obj, (_JLocalDateTime, _JLocalDate)):
        # 时间类型 → ISO-8601 字符串(测试侧 pd.to_datetime 可与 pandas datetime64 对照)
        return str(obj)
    if isinstance(obj, _JNumber):
        if isinstance(obj, (_JDouble, _JFloat)):
            v = float(obj)
            # 复刻旧 JSON 协议语义:NaN → null(Python None),测试断言按 None 编写。
            # Infinity 不折叠为 None —— 否则桥上无法区分"溢出得 ±inf"与"NaN"
            # (溢出场景需断言 isinf);NaN 保持 None 不变。
            return None if math.isnan(v) else v
        return int(obj)
    if isinstance(obj, _JList):
        return [_to_py(x) for x in obj]
    # 数组兜底:boolean[]/long[]/double[]/Object[] 均可迭代
    try:
        return [_to_py(x) for x in obj]
    except Exception:
        return obj


class JianClient:
    """JPype 客户端:JVM 常驻,直接调 JianJpypeBridge 静态方法。"""

    def __init__(self) -> None:
        _ensure_jvm()
        self._bridge = jpype.JClass(_BRIDGE_CLASS)

    # ===== 算子封装(签名与旧版 subprocess 客户端完全一致)=====

    def sort(self, df: dict, col: str, asc: bool) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.sort(c, r, col, asc))

    def filter(self, df: dict, expr: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.filter(c, r, expr))

    def head(self, df: dict, n: int) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.head(c, r, n))

    def tail(self, df: dict, n: int) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.tail(c, r, n))

    def merge(self, left: dict, right: dict, how: str, on: str) -> dict:
        lc, lr = _to_java_df(left)
        rc, rr = _to_java_df(right)
        return _to_py(self._bridge.merge(lc, lr, rc, rr, how, on))

    def mergeOn(self, left: dict, right: dict, how: str, leftOn: str, rightOn: str) -> dict:
        """异名键 merge(leftOn!=rightOn),pandas 对照用。"""
        lc, lr = _to_java_df(left)
        rc, rr = _to_java_df(right)
        return _to_py(self._bridge.mergeOn(lc, lr, rc, rr, how, leftOn, rightOn))

    def groupBy(self, df: dict, by: str, col: str, fn: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.groupBy(c, r, by, col, fn))

    def concat(self, dfs: list[dict], axis: int) -> dict:
        # 因为硬索引 dfs[0]/dfs[1] 在单元素时抛裸 IndexError(无算子信息)且不支持 >2 个,
        # 所以做长度校验 + 变长折叠(逐对 concat)。
        if len(dfs) < 2:
            raise ValueError(f"concat 至少需要 2 个 DataFrame,实际 {len(dfs)}")
        acc = dfs[0]
        for nxt in dfs[1:]:
            c1, r1 = _to_java_df(acc)
            c2, r2 = _to_java_df(nxt)
            acc = _to_py(self._bridge.concat(c1, r1, c2, r2, axis))
        return acc

    def dropDuplicates(self, df: dict, subset: list[str]) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.dropDuplicates(c, r, _to_java_string_list(subset)))

    def fillna(self, df: dict, value) -> dict:
        c, r = _to_java_df(df)
        if isinstance(value, dict):
            # dict 形式经平行列表转发 fillnaDict(对齐 pandas fillna(dict))
            keys = list(value.keys())
            vals = [value[k] for k in keys]
            return _to_py(self._bridge.fillnaDict(c, r, _to_java_string_list(keys), _to_java_object_list(vals)))
        return _to_py(self._bridge.fillna(c, r, value))

    def dropna(self, df: dict) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.dropna(c, r))

    def ffill(self, df: dict) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.ffill(c, r))

    def astype(self, df: dict, col: str, target: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.astype(c, r, col, target))

    def select(self, df: dict, cols: list[str]) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.select(c, r, _to_java_string_list(cols)))

    def drop(self, df: dict, cols: list[str]) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.drop(c, r, _to_java_string_list(cols)))

    def slice(self, df: dict, a: int, b: int) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.slice(c, r, a, b))

    def nlargest(self, df: dict, n: int, col: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.nlargest(c, r, n, col))

    def nsmallest(self, df: dict, n: int, col: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.nsmallest(c, r, n, col))

    def colAdd(self, df: dict, newName: str, a: str, b: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.colAdd(c, r, newName, a, b))

    def colSub(self, df: dict, newName: str, a: str, b: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.colSub(c, r, newName, a, b))

    def colDiv(self, df: dict, newName: str, a: str, b: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.colDiv(c, r, newName, a, b))

    def colMulScalar(self, df: dict, newName: str, src: str, k: float) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.colMulScalar(c, r, newName, src, k))

    def assign(self, df: dict, newName: str, value: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.assign(c, r, newName, value))

    # ===== 高频实用扩展 =====

    def idxmax(self, df: dict, col: str) -> int:
        c, r = _to_java_df(df)
        return int(_to_py(self._bridge.idxmax(c, r, col))["idx"])

    def idxmin(self, df: dict, col: str) -> int:
        c, r = _to_java_df(df)
        return int(_to_py(self._bridge.idxmin(c, r, col))["idx"])

    def duplicated(self, df: dict, subset: list[str] | None, keep: str = "first") -> list[bool]:
        c, r = _to_java_df(df)
        return list(_to_py(self._bridge.duplicated(c, r, _to_java_string_list(subset), keep))["mask"])

    def sample(self, df: dict, n: int, replace: bool, seed: int) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.sample(c, r, n, replace, seed))

    def isin(self, df: dict, values: list) -> list[bool]:
        c, r = _to_java_df(df)
        return list(_to_py(self._bridge.isin(c, r, _to_java_object_list(values)))["mask"])

    def colIsin(self, df: dict, col: str, values: list) -> list[bool]:
        assert col in df["columns"], f"col={col} 未在 columns={df['columns']} 中注册"
        idx = df["columns"].index(col)
        single = {"columns": [col], "rows": [[row[idx]] for row in df["rows"]]}
        return self.isin(single, values)

    def where(self, df: dict, cond: list[bool], other) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.where(c, r, _to_java_bool_list(cond), other))

    def colCmp(self, df: dict, col: str, op: str, value) -> list[bool]:
        c, r = _to_java_df(df)
        return list(_to_py(self._bridge.colCmp(c, r, col, op, value))["mask"])

    def mask(self, df: dict, cond: list[bool], other) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.mask(c, r, _to_java_bool_list(cond), other))

    # ===== 统计扩展 =====

    def cumsum(self, df: dict, col: str, new_col: str = None) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.cumsum(c, r, col, new_col))

    def diff(self, df: dict, col: str, periods: int = 1, new_col: str = None) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.diff(c, r, col, periods, new_col))

    def pct_change(self, df: dict, col: str, periods: int = 1, new_col: str = None) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.pctChange(c, r, col, periods, new_col))

    def clip(self, df: dict, col: str, lower: float, upper: float, new_col: str = None) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.clip(c, r, col, lower, upper, new_col))

    def quantile(self, df: dict, col: str, q: float) -> float:
        c, r = _to_java_df(df)
        return float(_to_py(self._bridge.quantile(c, r, col, q))["value"])

    def rank(self, df: dict, col: str, method: str = "average", new_col: str = None) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.rank(c, r, col, method, new_col))

    def round(self, df: dict, col: str, decimals: int = 0, new_col: str = None) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.round(c, r, col, decimals, new_col))

    def prod(self, df: dict, col: str) -> float:
        c, r = _to_java_df(df)
        return float(_to_py(self._bridge.prod(c, r, col))["value"])

    # ===== 重塑合并扩展 =====

    def pivot(self, df: dict, index: str, columns: str, values: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.pivot(c, r, index, columns, values))

    def pivotTable(self, df: dict, index: str, columns: str, values: str, aggFn: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.pivotTable(c, r, index, columns, values, aggFn))

    def explode(self, df: dict, col: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.explode(c, r, col))

    def mergeAsof(self, left: dict, right: dict, on: str) -> dict:
        lc, lr = _to_java_df(left)
        rc, rr = _to_java_df(right)
        return _to_py(self._bridge.mergeAsof(lc, lr, rc, rr, on))

    def resample(self, df: dict, ts_col: str, rule: str, col: str, fn: str = "sum") -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.resample(c, r, ts_col, rule, col, fn))

    # ===== 统计/Window 扩展(用 pandas 详细逐值对照) =====

    def stat(self, df: dict, col: str, fn: str) -> float:
        c, r = _to_java_df(df)
        v = _to_py(self._bridge.stat(c, r, col, fn))["value"]
        return float("nan") if v is None else float(v)

    def corr(self, df: dict, x: str, y: str, method: str = "pearson") -> float:
        # NaN 结果(无定义相关)在桥传输层变 None → 还原为 nan
        c, r = _to_java_df(df)
        v = _to_py(self._bridge.corr(c, r, x, y, method))["value"]
        return float("nan") if v is None else float(v)

    def cov(self, df: dict, x: str, y: str) -> float:
        c, r = _to_java_df(df)
        v = _to_py(self._bridge.cov(c, r, x, y))["value"]
        return float("nan") if v is None else float(v)

    def valueCounts(self, df: dict, col: str) -> dict:
        c, r = _to_java_df(df)
        return _to_py(self._bridge.valueCounts(c, r, col))["counts"]

    def rolling(self, df: dict, col: str, window: int, fn: str) -> list:
        c, r = _to_java_df(df)
        return list(_to_py(self._bridge.rolling(c, r, col, window, fn))["values"])

    def ewm(self, df: dict, col: str, alpha: float, fn: str) -> list:
        c, r = _to_java_df(df)
        return list(_to_py(self._bridge.ewm(c, r, col, alpha, fn))["values"])

    def expanding(self, df: dict, col: str, fn: str) -> list:
        c, r = _to_java_df(df)
        return list(_to_py(self._bridge.expanding(c, r, col, fn))["values"])


# === 模块级单例(测试复用,JVM 常驻,避免每个测试重启) ===

_client: JianClient | None = None


def get_client() -> JianClient:
    global _client
    if _client is None:
        _client = JianClient()
    return _client


def close_client() -> None:
    """释放客户端引用。注意:不 shutdownJVM —— JPype 推荐进程结束时自然回收,
    主动 shutdown 在 pytest 多 session 场景反而引发问题(skill 模板同样建议)。"""
    global _client
    _client = None


# === DataFrame 构造辅助(供 Hypothesis 测试用)===

def make_df(columns: list[str], rows: list[list[Any]]) -> dict:
    """按 jian 的 DataFrame JSON 形态构造 df(与旧协议一致的 dict 结构)。"""
    return {"columns": columns, "rows": rows}