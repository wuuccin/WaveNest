package com.waterlogistics.algo.estimation;

import java.util.List;

/**
 * 航道中心线几何——经纬度 ↔ <b>弧长 {@code s} / 横向偏移 {@code e}</b> 的双向投影。
 *
 * <p>依据 {@code 80-A组算法方案} §3.2.4「步骤 B：曲线坐标投影」：把二维经纬度压到
 * 沿航道的一维坐标上，是 A 组后续所有算法的<b>坐标系基础</b>——沿航道一维卡尔曼（§3.2.5）
 * 在 {@code s} 上做、横向 OU（§3.2.6）在 {@code e} 上做、空间闸门（§3.1.3）判 {@code s}
 * 是否落在服务航段内、时间可行性下界按 {@code s} 线上最近距离算。
 *
 * <h3>坐标与里程口径（重要，勿与"河道里程"混淆）</h3>
 * <ul>
 *   <li><b>本类不内置任何坐标</b>：折线顶点由调用方注入。原因：《赣江场景项目文档》§3.2
 *       的桥梁坐标为公开地图示意值、<b>可能为 GCJ-02</b>，须先按项目统一坐标基准
 *       （CGCS2000/WGS84）转换；<b>不得把不同坐标系数据混用</b>。</li>
 *   <li><b>里程是折线累计长度，不是河道实测里程</b>：以三座桥点连线为例，全程约 5.78 km
 *       是"桥点间直线距离"之和（文档 §3.2 明示），非沿河实测。用于演示与算法口径自洽，
 *       对外不得表述为实测河道里程。</li>
 *   <li>平面近似：1° 纬度 ≈ {@value #METERS_PER_DEG_LAT} m，1° 经度按参考纬度余弦缩放。
 *       赣江航段跨度 ~5.8 km，该近似误差远小于算法其他环节，演示期可接受。</li>
 * </ul>
 *
 * <h3>{@code e} 的符号约定</h3>
 * <p>沿折线<b>前进方向</b>看，<b>右侧为正、左侧为负</b>；{@code |e|} 即该点到中心线的
 * 横向距离。若需"左右"语义，调用方结合航行方向判断（内河双向航道这一点必须注意：
 * 上行与下行的"右"是相反的）。
 */
public final class WaterwayGeometry {

    /** 1° 纬度对应的米数（球面近似，与 {@link ConstantVelocityPredictor#METERS_PER_DEG_LAT} 同源）。 */
    public static final double METERS_PER_DEG_LAT = ConstantVelocityPredictor.METERS_PER_DEG_LAT;

    /**
     * 折线顶点。
     *
     * @param lon 经度（十进制度，须为项目统一坐标基准）
     * @param lat 纬度（十进制度）
     */
    public record Vertex(double lon, double lat) {
    }

    /**
     * 投影结果。
     *
     * @param s 沿航道累计弧长，m（从折线首点起算）
     * @param e 横向偏移，m（前进方向右侧为正、左侧为负）
     */
    public record Projection(double s, double e) {

        /** 横向距离的绝对值（到中心线的最近距离），m。 */
        public double crossTrackDistanceM() {
            return Math.abs(e);
        }
    }

    private final double[] vertexLon;
    private final double[] vertexLat;

    /** 每段起点处的累计弧长，m；最后一项为全线总长。 */
    private final double[] cumulativeLengthM;

    /**
     * 构造航道中心线。
     *
     * @param vertices 折线顶点，至少 2 个，按航道走向排列（上行或下行均可，但须一致）
     * @throws IllegalArgumentException 顶点少于 2 个、或相邻顶点重合（零长段无法定义方向）
     */
    public WaterwayGeometry(List<Vertex> vertices) {
        if (vertices == null || vertices.size() < 2) {
            throw new IllegalArgumentException("航道折线至少需要 2 个顶点，实际 " +
                    (vertices == null ? 0 : vertices.size()));
        }

        int count = vertices.size();
        this.vertexLon = new double[count];
        this.vertexLat = new double[count];
        for (int i = 0; i < count; i++) {
            Vertex v = vertices.get(i);
            if (!Double.isFinite(v.lon()) || v.lon() < -180.0 || v.lon() > 180.0) {
                throw new IllegalArgumentException("第 " + i + " 个顶点经度非法：" + v.lon());
            }
            if (!Double.isFinite(v.lat()) || v.lat() < -90.0 || v.lat() > 90.0) {
                throw new IllegalArgumentException("第 " + i + " 个顶点纬度非法：" + v.lat());
            }
            vertexLon[i] = v.lon();
            vertexLat[i] = v.lat();
        }

        this.cumulativeLengthM = new double[count];
        cumulativeLengthM[0] = 0.0;
        for (int i = 1; i < count; i++) {
            double segment = segmentLengthM(i - 1, i);
            if (segment <= 0.0) {
                throw new IllegalArgumentException("第 " + (i - 1) + "→" + i + " 段长度为 0，无法定义方向");
            }
            cumulativeLengthM[i] = cumulativeLengthM[i - 1] + segment;
        }
    }

    /** 全线累计弧长，m。 */
    public double totalLengthM() {
        return cumulativeLengthM[cumulativeLengthM.length - 1];
    }

    /** 顶点个数。 */
    public int vertexCount() {
        return vertexLon.length;
    }

    /**
     * 各段起点的累计弧长（升序，首项 0、末项为全线总长），m。
     *
     * <p>返回<b>副本</b>，调用方改它不影响几何对象。用途：步骤 E 的沿程推进需要知道
     * 「下一个航段边界还有多远」，以便在新航段限速处重新起算（{@code 80} §3.2.7 第 3 步
     * 的「分段处理」），不能靠试探 {@link #courseDegAt} 的跳变来猜边界。
     *
     * @return 长度等于顶点数的累计弧长数组副本
     */
    public double[] segmentBoundariesM() {
        return cumulativeLengthM.clone();
    }

    /**
     * 投影：经纬度 →（弧长 {@code s}，横向 {@code e}）。
     *
     * <p>对每一段求垂足（限制在段内，端点外则取端点），取横向距离最小的那一段。
     * 折线接近自身（如急弯、回折）时，本方法取<b>最近</b>段，可能跳段——这是折线近似的
     * 固有性质；内河航段弯曲度小、折线点数少时不影响演示。
     *
     * @param lon 经度
     * @param lat 纬度
     * @return 投影结果
     */
    public Projection project(double lon, double lat) {
        int bestSegment = 0;
        double bestAlongRatio = 0.0;
        double bestDistance = Double.MAX_VALUE;
        double bestSignedCrossM = 0.0;

        for (int i = 0; i < vertexLon.length - 1; i++) {
            // 以段起点为原点，把点与段都换算成米（x=东、y=北）
            double latRef = (vertexLat[i] + vertexLat[i + 1]) / 2.0;
            double metersPerDegLon = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(latRef));

            double segX = (vertexLon[i + 1] - vertexLon[i]) * metersPerDegLon;
            double segY = (vertexLat[i + 1] - vertexLat[i]) * METERS_PER_DEG_LAT;
            double pointX = (lon - vertexLon[i]) * metersPerDegLon;
            double pointY = (lat - vertexLat[i]) * METERS_PER_DEG_LAT;

            double segLengthSquared = segX * segX + segY * segY;
            double ratio = (pointX * segX + pointY * segY) / segLengthSquared;
            ratio = Math.max(0.0, Math.min(1.0, ratio));   // 垂足限制在段内

            double closestX = ratio * segX;
            double closestY = ratio * segY;
            double dx = pointX - closestX;
            double dy = pointY - closestY;
            double distance = Math.hypot(dx, dy);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestSegment = i;
                bestAlongRatio = ratio;
                // 叉积 > 0 表示点在行进方向左侧；取负号使"右侧为正"
                bestSignedCrossM = -(segX * pointY - segY * pointX) / Math.sqrt(segLengthSquared);
            }
        }

        double segmentLength = cumulativeLengthM[bestSegment + 1] - cumulativeLengthM[bestSegment];
        double s = cumulativeLengthM[bestSegment] + bestAlongRatio * segmentLength;
        return new Projection(s, bestSignedCrossM);
    }

    /**
     * 反投影：由弧长 {@code s} 求中心线上的点。
     *
     * @param s 沿航道累计弧长，m；越界自动夹到 {@code [0, totalLengthM()]}
     * @return 中心线顶点坐标（线性插值）
     */
    public Vertex pointAt(double s) {
        double clamped = Math.max(0.0, Math.min(totalLengthM(), s));

        for (int i = 0; i < vertexLon.length - 1; i++) {
            double segmentStart = cumulativeLengthM[i];
            double segmentEnd = cumulativeLengthM[i + 1];
            if (clamped > segmentEnd && i < vertexLon.length - 2) {
                continue;
            }
            double segmentLength = segmentEnd - segmentStart;
            double ratio = segmentLength <= 0.0 ? 0.0 : (clamped - segmentStart) / segmentLength;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            return new Vertex(
                    vertexLon[i] + ratio * (vertexLon[i + 1] - vertexLon[i]),
                    vertexLat[i] + ratio * (vertexLat[i + 1] - vertexLat[i]));
        }
        return new Vertex(vertexLon[vertexLon.length - 1], vertexLat[vertexLat.length - 1]);
    }

    /**
     * 中心线上两点间的弧长差（用于算"沿航道还有多远"）。
     *
     * @param fromS 起点弧长
     * @param toS   终点弧长
     * @return 带符号的弧长差 {@code toS − fromS}，m（负值表示逆向）
     */
    public static double arcDistanceM(double fromS, double toS) {
        return toS - fromS;
    }

    /**
     * 中心线在弧长 {@code s} 处的<b>走向</b>（度，正北 0、顺时针），即该段的方位角。
     *
     * <p>用途：沿航道一维卡尔曼的初始化按 {@code v_s = sog · cos(ΔCOG)} 算纵向速度，
     * 其中 {@code ΔCOG = cog − 航道走向}（{@code 80} §3.2.5 初始化口径）。
     *
     * <p><b>内河双向航道注意</b>：本方法给的是中心线<b>固定正向</b>的走向；上行船的实际
     * 航向与之相差约 180°，故 {@code v_s} 会稳定为负——这是<b>正常工况</b>，不是掉头/逆向
     * （§3.2.5 双向航道口径）。
     *
     * @param s 沿航道弧长，m；越界夹到 {@code [0, totalLengthM()]}
     * @return 该处的航道走向，度
     */
    public double courseDegAt(double s) {
        double clamped = Math.max(0.0, Math.min(totalLengthM(), s));

        for (int i = 0; i < vertexLon.length - 1; i++) {
            double segmentEnd = cumulativeLengthM[i + 1];
            boolean lastSegment = i == vertexLon.length - 2;
            if (clamped > segmentEnd && !lastSegment) {
                continue;
            }
            return ConstantVelocityPredictor.bearingDeg(
                    vertexLon[i], vertexLat[i], vertexLon[i + 1], vertexLat[i + 1]);
        }
        int last = vertexLon.length - 1;
        return ConstantVelocityPredictor.bearingDeg(
                vertexLon[last - 1], vertexLat[last - 1], vertexLon[last], vertexLat[last]);
    }

    // ───────────── 内部工具 ─────────────

    private double segmentLengthM(int fromIndex, int toIndex) {
        double latRef = (vertexLat[fromIndex] + vertexLat[toIndex]) / 2.0;
        double metersPerDegLon = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(latRef));
        double dx = (vertexLon[toIndex] - vertexLon[fromIndex]) * metersPerDegLon;
        double dy = (vertexLat[toIndex] - vertexLat[fromIndex]) * METERS_PER_DEG_LAT;
        return Math.hypot(dx, dy);
    }
}
