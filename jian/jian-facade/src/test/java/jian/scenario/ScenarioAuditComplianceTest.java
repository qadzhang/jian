package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ScenarioAuditComplianceTest —— 真实场景测试(审计合规域,S45/S46)
// │  Why  : 依赖漏洞审计与配置合规校验是安全/运维季度审计的两类通用需求,须进真实场景集
// │         (四轨红线:场景登记 scenarios.md,完整源码随 jar 分发到 META-INF/ai/scenarios-src/)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S45/S46 行
// │  When : mvn test(jian-facade 模块);S45 依赖清单落临时 xml 再 readXml 读回
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioAuditComplianceTest.java
// │  How  : 数据走向:(S45)构建文件(pom 片段)落盘 → Xml.read(rowName=dep)解析成表 →
// │         与 CVE 清单 merge(inner,按坐标)→ query(分数≥7)→ groupBy(服务).agg 高危清单;
// │         (S46)配置清单 → query(端口/超时越界,|| 组合条件)→ 违规服务集合断言。
class ScenarioAuditComplianceTest {

    @TempDir Path tmp;

    // S45 依赖版本漏洞审计:解析各服务依赖清单,关联 CVE 库,出高危服务清单
    @Test
    void S45_依赖漏洞审计() throws Exception {
        // 三个服务的依赖清单(pom 片段,模拟批量拉取后的聚合件)
        String pomFragment = """
                <deps>
                  <dep><服务>order-svc</服务><坐标>org.apache:poi:5.5.1</坐标></dep>
                  <dep><服务>user-svc</服务><坐标>com.fasterxml:jackson:2.18.2</坐标></dep>
                  <dep><服务>pay-svc</服务><坐标>org.apache:log4j:2.14.1</坐标></dep>
                </deps>
                """;
        Path pom = tmp.resolve("deps.xml");
        Files.writeString(pom, pomFragment);
        DataFrame deps = jian.io.xml.Xml.read(pom.toString()).rowName("dep").go();
        assertThat(deps.rowCount()).isEqualTo(3);
        // CVE 清单(生产环境来自 NVD 导出,此处为受控样本)
        DataFrame cve = DataFrame.of(Schema.of("坐标", DType.STRING, "分数", DType.DOUBLE),
                new Object[][]{
                        {"org.apache:poi:5.5.1", 3.1},
                        {"com.fasterxml:jackson:2.18.2", 2.0},
                        {"org.apache:log4j:2.14.1", 10.0}});
        // 关联 + 阈值过滤:只有 log4j(10.0 ≥ 7)是高危
        DataFrame risky = deps.merge(cve, "inner", "坐标").query("分数 >= 7");
        assertThat(risky.rowCount()).isEqualTo(1);
        assertThat(risky.getColumn("服务").toObjectArray()).containsExactly("pay-svc");
        assertThat(((Number) risky.getColumn("分数").get(0)).doubleValue()).isEqualTo(10.0);
        // 按服务聚合口径可用:高危服务数 = 1
        DataFrame bySvc = risky.groupBy("服务").agg(java.util.Map.of("坐标", "count"));
        assertThat(bySvc.rowCount()).isEqualTo(1);
        // 低分依赖(poi/jackson)不允许进高危清单
        assertThat(risky.getColumn("坐标").toObjectArray())
                .doesNotContain("org.apache:poi:5.5.1", "com.fasterxml:jackson:2.18.2");
        // SQL 对照版:JOIN + WHERE 一条出高危清单(与 merge+query 差分;限定名仅用于 ON)
        DataFrame sqlRisk = Jian.sql("""
                SELECT 服务, 坐标, 分数 FROM ${d} JOIN ${c} ON d.坐标 = c.坐标 WHERE 分数 >= 7
                """, deps, cve);
        assertThat(sqlRisk.rowCount()).isEqualTo(risky.rowCount()).isEqualTo(1);
        assertThat(sqlRisk.getColumn("服务").toObjectArray()).containsExactly("pay-svc");
        assertThat(((Number) sqlRisk.getColumn("分数").get(0)).doubleValue()).isEqualTo(10.0);
    }

    // S46 配置合规批量校验:端口区间与超时阈值越界即违规
    @Test
    void S46_配置合规校验() throws Exception {
        DataFrame df = DataFrame.of(Schema.of(
                        "服务", DType.STRING, "端口", DType.LONG, "超时ms", DType.LONG),
                new Object[][]{
                        {"svc1", 8080L, 3000L},   // 合规
                        {"svc2", 7000L, 2000L},   // 端口低于下限
                        {"svc3", 8500L, 9000L},   // 超时超阈值
                        {"svc4", 9500L, 1000L}}); // 端口高于上限
        DataFrame violations = df.query("端口 < 8000 || 端口 > 9000 || 超时ms > 5000");
        assertThat(violations.rowCount()).isEqualTo(3);
        assertThat(violations.getColumn("服务").toObjectArray())
                .containsExactlyInAnyOrder("svc2", "svc3", "svc4");
        // 合规服务不误报
        assertThat(violations.getColumn("服务").toObjectArray()).doesNotContain("svc1");
        // 落盘审计报告(Markdown)再读回核对内容完整性
        Path rpt = tmp.resolve("配置审计.md");
        Jian.toMarkdown(violations.select("服务", "端口", "超时ms"), rpt.toString());
        String raw = Files.readString(rpt);
        assertThat(raw).contains("svc2").contains("svc3").contains("svc4").doesNotContain("svc1");
        // SQL 对照版:越界组合条件一条 WHERE 表达(与链式 query 差分)
        DataFrame sqlViol = Jian.sql(
                "SELECT 服务, 端口, 超时ms FROM ${t} WHERE 端口 < 8000 OR 端口 > 9000 OR 超时ms > 5000", df);
        assertThat(sqlViol.rowCount()).isEqualTo(violations.rowCount()).isEqualTo(3);
        assertThat(sqlViol.getColumn("服务").toObjectArray())
                .containsExactlyInAnyOrder("svc2", "svc3", "svc4");
    }
}
