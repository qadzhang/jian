package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SeriesWindowTest {

    @Test
    void series基础统计() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        assertThat(s.size()).isEqualTo(5);
        assertThat(s.mean()).isCloseTo(3.0, within(1e-10));
        assertThat(s.sum()).isCloseTo(15.0, within(1e-10));
        assertThat(s.min()).isEqualTo(1.0);
        assertThat(s.max()).isEqualTo(5.0);
    }

    @Test
    void series排序索引() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        int[] asc = s.sortIndicesAscending();
        // 1,2,3,4,5 → 索引 0,1,2,3,4
        assertThat(asc[0]).isEqualTo(0);
        int[] desc = s.sortIndicesDescending();
        assertThat(desc[0]).isEqualTo(4);
    }

    @Test
    void rolling均值() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        double[] ma3 = s.rolling(3).mean();
        assertThat(ma3.length).isEqualTo(5);
        assertThat(Double.isNaN(ma3[0])).isTrue();   // 前 2 个为 NaN
        assertThat(Double.isNaN(ma3[1])).isTrue();
        assertThat(ma3[2]).isCloseTo(2.0, within(1e-10));  // (1+2+3)/3
        assertThat(ma3[3]).isCloseTo(3.0, within(1e-10));  // (2+3+4)/3
        assertThat(ma3[4]).isCloseTo(4.0, within(1e-10));  // (3+4+5)/3
    }

    @Test
    void rolling最大最小() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        double[] mx = s.rolling(3).max();
        assertThat(mx[2]).isEqualTo(3.0);
        assertThat(mx[4]).isEqualTo(5.0);
        double[] mn = s.rolling(3).min();
        assertThat(mn[2]).isEqualTo(1.0);
        assertThat(mn[4]).isEqualTo(3.0);
    }

    @Test
    void rolling标准差() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        double[] sd = s.rolling(3).std();
        // 窗口 [1,2,3] 的样本标准差 = 1.0
        assertThat(sd[2]).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void expanding累积() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        double[] cs = s.expanding().sum();
        assertThat(cs[0]).isEqualTo(1.0);
        assertThat(cs[4]).isEqualTo(15.0);
        double[] cm = s.expanding().max();
        assertThat(cm[2]).isEqualTo(3.0);
    }

    @Test
    void ewm指数加权() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        double[] ewma = s.ewm(0.5).mean();
        // 第一个值 = data[0] = 1.0
        assertThat(ewma[0]).isEqualTo(1.0);
        // 第二个 = 0.5*2 + 0.5*1 = 1.5
        assertThat(ewma[1]).isCloseTo(1.5, within(1e-10));
    }

    @Test
    void series差分和pct_change() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        double[] d = s.diff(1);
        assertThat(d[0]).isNaN();
        assertThat(d[1]).isEqualTo(1.0);  // 2-1
        assertThat(d[4]).isEqualTo(1.0);  // 5-4

        double[] pc = s.pctChange(1);
        assertThat(pc[1]).isCloseTo(1.0, within(1e-10));  // (2-1)/1 = 1.0
    }

    @Test
    void multiIndex二级() {
        MultiIndex mi = MultiIndex.of(
                new Object[]{"RD", "RD", "PM"},
                new Object[]{1, 2, 3});
        assertThat(mi.size()).isEqualTo(3);
        assertThat(mi.getLevel0(0)).isEqualTo("RD");
        assertThat(mi.getLevel1(1)).isEqualTo(2);  // level1 = {1,2,3},index 1 = 2
    }

    @Test
    void series切片() {
        DataFrame df = sample();
        Series s = df.getSeries("price");
        Series head3 = s.head(3);
        assertThat(head3.size()).isEqualTo(3);
        Series tail2 = s.tail(2);
        assertThat(tail2.getDouble(0)).isEqualTo(4.0);
    }

    @Test
    void kahan精度() {
        // 大小悬殊的数组:1e16 + 1 - 1e16 应该 = 1(朴素累加可能丢)
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1e16}, {1.0}, {2.0}, {-1e16}});
        double sum = df.colSum("v");
        // 朴素:1e16+1 = 1e16(丢1),+2=1e16(丢2),-1e16=0;Kahan:应得 3.0
        // 注:Kahan 精度对这种极端场景也有限,但比朴素好
        assertThat(sum).isCloseTo(3.0, within(1.0));  // 容忍 < 1 的误差
    }

    private DataFrame sample() {
        return DataFrame.of(
                Schema.of("price", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
    }
}
