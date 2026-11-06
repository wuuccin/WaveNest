package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSettings;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.NestMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AdmissionInputAssembler} 的接货况判据单测（M2 · 卢肄文 · 11-05 序号 2）。
 *
 * <p>要验的不是「查库调通了」，而是<b>三态语义</b>：{@code null}（跳过并可见）／
 * {@code false}（延后等货）／{@code true}（放行），以及在什么情况下落哪一态。
 * 这三态一旦错位，后果完全不同：
 * <ul>
 *   <li>把「查无货行」判成 {@code false} → 每一笔正常下单都延后等一个永远不来的
 *       {@code GOODS_READY}，主链停摆；</li>
 *   <li>把「货未就绪」判成 {@code null} → 判定链记一条 skipped 就放行，
 *       「进调度即货已备好」这条隐式承诺被打破。</li>
 * </ul>
 *
 * <p>不依赖 Spring 与数据库：货物表用 JDK 代理桩。
 */
class AdmissionInputAssemblerTest {

    private static final String ORDER_ID = "ORD-TEST-1";

    private static final double SHIP_LON = 115.8590;
    private static final double SHIP_LAT = 28.6630;

    /** 用给定的货物行搭装配器；{@code rows == null} 表示不装配货物表。 */
    private static AdmissionInputAssembler assembler(List<GoodsEntity> rows) {
        PredictionService predictionService = new PredictionService(
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L),
                mmsi -> Optional.empty(), event -> { });
        NestMapper nestMapper = (NestMapper) Proxy.newProxyInstance(
                NestMapper.class.getClassLoader(), new Class<?>[]{NestMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName())
                        ? List.<NestEntity>of() : null);
        GoodsMapper goodsMapper = rows == null ? null : (GoodsMapper) Proxy.newProxyInstance(
                GoodsMapper.class.getClassLoader(), new Class<?>[]{GoodsMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName())
                        ? List.copyOf(rows) : null);
        return new AdmissionInputAssembler(predictionService, nestMapper,
                new DispatchGateService(),
                "115.852,28.646;115.859,28.663;115.879,28.692", 0.0, 0.0, 50.0,
                "医疗物资,应急药品,食品,生活物资", 50.0, 0.1, 10_000L, 20.0, 60.0,
                60L, 60L, 1800L, 0.5, 300L, 1800L, goodsMapper);
    }

    private static AdmissionPipeline.OrderRequest request(String cargoNestId) {
        return new AdmissionPipeline.OrderRequest(
                ORDER_ID, "413000001", "医疗物资", 5.0, null, "NORMAL", null, cargoNestId);
    }

    private static GoodsEntity goods(String status, String location) {
        GoodsEntity row = new GoodsEntity();
        row.setGoodsId("G-" + status + "-" + location);
        row.setOrderId(ORDER_ID);
        row.setStatus(status);
        row.setLocation(location);
        return row;
    }

    private static Boolean readyOf(AdmissionInputAssembler assembler, String cargoNestId) {
        return assembler.assemble(request(cargoNestId), System.currentTimeMillis(), List.of())
                .goodsReady();
    }

    @Test
    @DisplayName("未装配货物表 → null（跳过并可见，不当作「货没好」）")
    void noGoodsMapper_shouldSkipCriterion() {
        assertNull(readyOf(assembler(null), null),
                "没有数据源时判 null，判定链会记 GOODS_READY 进 skippedCriteria");
    }

    @Test
    @DisplayName("★ 查无货物行 → null，而不是 false（否则整条主链停摆）")
    void noGoodsRows_shouldSkipNotBlock() {
        AdmissionInputAssembler assembler = assembler(List.of());

        assertNull(readyOf(assembler, null),
                "货行由下单落库时建立；尚未建行是「数据源缺失」而不是「货没好」。"
                        + "按 false 处理会让每笔正常下单都延后等一个永不到来的事件");
    }

    @Test
    @DisplayName("已就绪且未指定货巢 → true（只按状态判）")
    void readyWithoutNest_shouldPass() {
        AdmissionInputAssembler assembler = assembler(
                List.of(goods(GoodsStatus.READY.getValue(), null)));

        assertEquals(Boolean.TRUE, readyOf(assembler, null));
    }

    @Test
    @DisplayName("★ 未就绪 → false（延后等 GOODS_READY）")
    void notReady_shouldBlock() {
        AdmissionInputAssembler assembler = assembler(
                List.of(goods(GoodsStatus.NOT_READY.getValue(), "N-1")));

        assertEquals(Boolean.FALSE, readyOf(assembler, null),
                "货没好就进调度，等于把「先派后等」当成正常流程");
    }

    @Test
    @DisplayName("★ 一单多货行：任一行未就绪即整单判否（不半单先飞）")
    void multipleRows_shouldRequireAllReady() {
        AdmissionInputAssembler assembler = assembler(List.of(
                goods(GoodsStatus.READY.getValue(), "N-1"),
                goods(GoodsStatus.NOT_READY.getValue(), "N-1")));

        assertEquals(Boolean.FALSE, readyOf(assembler, null));
    }

    @Test
    @DisplayName("指定货巢且货物位置不同 → false（货备好了但不在这个巢）")
    void readyButAtOtherNest_shouldBlock() {
        AdmissionInputAssembler assembler = assembler(
                List.of(goods(GoodsStatus.READY.getValue(), "N-OTHER")));

        assertEquals(Boolean.FALSE, readyOf(assembler, "N-1"));
    }

    @Test
    @DisplayName("指定货巢且货物位置一致 → true")
    void readyAtSameNest_shouldPass() {
        AdmissionInputAssembler assembler = assembler(
                List.of(goods(GoodsStatus.READY.getValue(), "N-1")));

        assertEquals(Boolean.TRUE, readyOf(assembler, "N-1"));
    }

    @Test
    @DisplayName("★ 指定货巢但货物未填位置 → 仍放行（可空列不得变成全局死锁）")
    void readyWithNullLocation_shouldPass() {
        AdmissionInputAssembler assembler = assembler(
                List.of(goods(GoodsStatus.READY.getValue(), null)));

        assertEquals(Boolean.TRUE, readyOf(assembler, "N-1"),
                "location 是 001_init.sql 的可空列，下单时通常不填；"
                        + "把 NULL 判成「不在指定巢」会让这些货永远等不到派单");
    }

    @Test
    @DisplayName("后置状态（已装机）不是「已就绪」→ false（装机后不能再当待装货派）")
    void loaded_shouldBlock() {
        AdmissionInputAssembler assembler = assembler(
                List.of(goods(GoodsStatus.LOADED.getValue(), "N-1")));

        assertEquals(Boolean.FALSE, readyOf(assembler, null));
    }

    @Test
    @DisplayName("查库抛异常 → null（不把一次下单打成 500）")
    void queryFailure_shouldSkipInsteadOfThrow() {
        GoodsMapper boom = (GoodsMapper) Proxy.newProxyInstance(
                GoodsMapper.class.getClassLoader(), new Class<?>[]{GoodsMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        throw new IllegalStateException("db down");
                    }
                    return null;
                });
        PredictionService predictionService = new PredictionService(
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L),
                mmsi -> Optional.empty(), event -> { });
        NestMapper nestMapper = (NestMapper) Proxy.newProxyInstance(
                NestMapper.class.getClassLoader(), new Class<?>[]{NestMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName())
                        ? List.<NestEntity>of() : null);
        AdmissionInputAssembler assembler = new AdmissionInputAssembler(predictionService,
                nestMapper, new DispatchGateService(),
                "115.852,28.646;115.859,28.663;115.879,28.692", 0.0, 0.0, 50.0,
                "医疗物资,应急药品,食品,生活物资", 50.0, 0.1, 10_000L, 20.0, 60.0,
                60L, 60L, 1800L, 0.5, 300L, 1800L, boom);

        assertNull(readyOf(assembler, null), "查库故障按「未判定」处理，不升级成下单失败");
    }

    @Test
    @DisplayName("三态原样透传给判定链：null／false／true 各自对应 skip／延后／放行")
    void goodsReady_shouldBePassedThroughVerbatim() {
        // 判定链对这三态的处理（skippedCriteria／DEFER(GOODS_NOT_READY)／继续）已在
        // AdmissionPipelineTest ⑦ 覆盖；这里只钉住装配层不加工、不兜底：
        assertNull(readyOf(assembler(null), null));
        assertEquals(Boolean.FALSE, readyOf(assembler(
                List.of(goods(GoodsStatus.NOT_READY.getValue(), null))), null));
        assertEquals(Boolean.TRUE, readyOf(assembler(
                List.of(goods(GoodsStatus.READY.getValue(), null))), null));
    }

    @Test
    @DisplayName("多货行桩不受顺序影响：全就绪即放行")
    void allReadyRows_shouldPass() {
        List<GoodsEntity> rows = new ArrayList<>(List.of(
                goods(GoodsStatus.READY.getValue(), "N-1"),
                goods(GoodsStatus.READY.getValue(), "N-1")));

        assertEquals(Boolean.TRUE, readyOf(assembler(rows), "N-1"));
    }
}
