# jian-io-clipboard

## 基本信息
- **library**: jian
- **entryClass**: jian.io.clipboard.Clipboard
- **deps**: jian-core;纯 JDK(通过外部命令访问系统剪贴板,不绑特定程序路径)
- **tests**: 8

## 摘要
跨平台系统剪贴板读写,对齐 pandas.read_clipboard / to_clipboard;数据以 TSV 格式传输,粘贴到 Excel/WPS 自动分列。

## 能力
- 写 `Clipboard.write(df)`:DataFrame → TSV(制表符 + 换行)→ 剪贴板命令 stdin
- 读 `Clipboard.read()`:剪贴板命令 stdout → TSV → 推断类型 → DataFrame
- 跨平台运行时探测:Linux(xclip / xsel)、macOS(pbcopy / pbpaste)、Windows(clip / powershell Get-Clipboard)
- 降级策略:命令不存在时不崩溃,降级到同 JVM 内存变量(可读回),并打 warning


### 行为细节
- TSV 不 trim(与 Csv 同口径);空表头字段兜底 _0/_1;resetMemoryFallback() 公开(命令恢复后清降级缓存)
- 写异常路径回收子进程;读失败退出码一次性提示

### 行为细节(续 1)
- TSV 写出公式注入防护(`= + - @` 开头加单引号前缀,与 Csv 一致)

## 限制
- 依赖外部命令;xclip/xsel 在 Linux 需用户自行安装(`apt install xclip`),否则降级
- 内存降级仅在同一个 JVM 内可读回,跨进程不可见
- 仅支持 TSV 单一剪贴板格式(不支持图片、RTF、HTML 剪贴板类型)

## 快速上手
```java
import jian.io.clipboard.Clipboard;
import jian.core.DataFrame;

// 复制到剪贴板(可直接粘进 Excel)
Clipboard.write(df);

// 从剪贴板读(如从 Excel/网页复制的表格)
DataFrame pasted = Clipboard.read();
```
