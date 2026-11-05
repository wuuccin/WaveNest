package com.waterlogistics.dispatch.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CandidateInputAssembler} 的逐字审查回归（MEDIUM.13 收敛后的唯一组装实现）。
 *
 * <h3>本类守的三件事</h3>
 * <ol>
 *   <li><b>入参 null 不打头就 NPE</b>：旧实现第一行就 {@code order.getShipLon()}，
 *       调用方漏判 null 时得到的异常消息里没有参数名，排障只能猜；</li>
 *   <li><b>被跳过的机巢不得连带丢机</b>：原先缺坐标的机巢 {@code continue} 之后，
 *       其上的无人机仍被塞进 {@code droneViews}，生成端 {@code nestMap.get(nestId)} 查不到
 *       再静默 {@code continue}——现场只看到「机明明空闲健康却不出候选」；</li>
 *   <li><b>机型串空白语义统一</b>：{@code null}／{@code ""}／{@code " , "} 都是「未登记不限制」，
 *       不能拆出 {@code [""]} 这种假机型把该巢候选清零。</li>
 * </ol>
 */
class CandidateInputAssemblerTest {

    private static OrderTaskEntity order() {
        OrderTaskEntity o = new OrderTaskEntity();
        o.setOrderId("ORD-ASSEMBLE-1");
        o.setShipLon(BigDecimal.valueOf(115.8611));
        o.setShipLat(BigDecimal.valueOf(28.6660));
        o.setGoodsWeight(BigDecimal.valueOf(2.0));
        return o;
    }

    private static NestEntity nest(String id, Double lon, Double lat, String models) {
        NestEntity n = new NestEntity();
        n.setNestId(id);
        n.setLon(lon == null ? null : BigDecimal.valueOf(lon));
        n.setLat(lat == null ? null : BigDecimal.valueOf(lat));
        n.setSupportedModels(models);
        n.setStatus("可用");
        return n;
    }

    private static DroneEntity drone(String id, String currentNestId, String model) {
        DroneEntity d = new DroneEntity();
        d.setDroneId(id);
        d.setHomeNestId(currentNestId);
        d.setCurrentNestId(currentNestId);
        d.setModel(model);
        d.setStatus("空闲");
        d.setHealthStatus("健康");
        d.setBattery(BigDecimal.valueOf(90));
        d.setMaxPayload(BigDecimal.valueOf(9));
        d.setWindLimitMs(BigDecimal.valueOf(10.0));
        d.setCruiseSpeedMs(BigDecimal.valueOf(12.0));
        return d;
    }

    // ───────────── 入参 null 守卫 ─────────────

    @Test
    @DisplayName("order 为 null 时抛带参数名的异常，而不是第一行就 NPE")
    void nullOrderFailsWithNamedParameter() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CandidateInputAssembler.assemble(null, List.of(), List.of()));
        assertTrue(ex.getMessage().contains("order"),
                "异常消息应指名是哪个参数坏了，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("drones/nests 为 null 时同样指名报错（查不到应传空列表）")
    void nullCollectionsFailWithNamedParameter() {
        OrderTaskEntity o = order();
        assertThrows(IllegalArgumentException.class,
                () -> CandidateInputAssembler.assemble(o, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> CandidateInputAssembler.assemble(o, List.of(), null));
    }

    // ───────────── 缺坐标机巢不得连带丢机 ─────────────

    /**
     * 缺坐标机巢上的无人机：必须在组装阶段就被剔掉并留痕，
     * 而不是「塞进去 → 生成端静默 continue」。
     *
     * <p>断言的是<b>组装产物本身</b>（{@code drones()} 里没有这架机）——
     * 如果只断言「生成结果为空」，把机器塞进去也能得到同样结果，测不出差别。
     */
    @Test
    @DisplayName("缺坐标机巢上的无人机【保留】在 droneViews，但该巢不进 nests（11-05 巢×机枚举口径）")
    void dronesOnCoordinateLessNestStayButThatNestIsDropped() {
        List<NestEntity> nests = List.of(
                nest("NEST-BAD", null, null, "M30T"),
                nest("NEST-OK", 115.848, 28.642, "M30T"));
        List<DroneEntity> drones = List.of(
                drone("UAV-ORPHAN", "NEST-BAD", "M30T"),
                drone("UAV-OK", "NEST-OK", "M30T"));

        CandidateInput input = CandidateInputAssembler.assemble(order(), drones, nests);

        assertEquals(1, input.nests().size(), "缺坐标机巢不进 nests——没有起点就组不出候选");
        // ★ 11-05 口径变更：原来这条断言的是「UAV-ORPHAN 被剔掉」，
        // 理由是「生成端会静默 continue」。巢×机双层枚举后，这架机<b>仍可从 NEST-OK 出发</b>
        // ——机型能力、电量、载荷都是机自身的属性，与从哪个巢起飞无关。
        // 所以正确做法是保留机器、剔除巢（巢缺坐标＝没有可用起点），而不是把机器也删掉：
        // 删掉机器等于把「本巢坏了 ⇒ 这架机今天不飞」写进组装层，正是硬判据 #1 要消除的行为。
        assertEquals(List.of("UAV-ORPHAN", "UAV-OK"),
                input.drones().stream().map(CandidateInput.DroneView::droneId).toList(),
                "机器一律保留（它可以从别的巢出发），只剔掉缺坐标的巢");
    }

    /** 归属巢号全空的机器无法做任何机型校验，仍在组装层剔除并留痕。 */
    @Test
    @DisplayName("巢号全空的无人机不进 droneViews（连机型校验都做不了）；巢不可用但巢号在的保留")
    void dronesWithoutAnyNestIdAreDropped() {
        List<NestEntity> nests = List.of(nest("NEST-OK", 115.848, 28.642, "M30T"));
        DroneEntity noNest = drone("UAV-NONEST", "NEST-OK", "M30T");
        noNest.setCurrentNestId(null);
        noNest.setHomeNestId(null);
        List<DroneEntity> drones = List.of(
                drone("UAV-OTHER", "NEST-DISABLED", "M30T"),
                noNest,
                drone("UAV-OK", "NEST-OK", "M30T"));

        CandidateInput input = CandidateInputAssembler.assemble(order(), drones, nests);

        // ★ 11-05 口径变更：UAV-OTHER 的归属巢 NEST-DISABLED 不在本轮可用巢里，
        // 但它<b>可以</b>从 NEST-OK 出发，故保留；只有巢号全空的 UAV-NONEST 剔除。
        assertEquals(List.of("UAV-OTHER", "UAV-OK"),
                input.drones().stream().map(CandidateInput.DroneView::droneId).toList(),
                "巢号全空的剔除；巢不可用但巢号在的保留（可跨巢起飞）");
    }

    /**
     * 端到端对照：剔除发生在组装层，故正常巢上的机器仍能生成候选。
     *
     * <p>这条是上面两条的「反向验证」——防止剔除逻辑写宽了，把正常机也一起剔掉。
     */
    @Test
    @DisplayName("反向验证：正常巢上的无人机照常生成候选")
    void healthyDroneStillGeneratesCandidate() {
        List<NestEntity> nests = List.of(nest("NEST-OK", 115.848, 28.642, "M30T, M400"));
        List<DroneEntity> drones = List.of(drone("UAV-OK", "NEST-OK", "M400"));

        CandidateInput input = CandidateInputAssembler.assemble(order(), drones, nests);
        List<CandidateGenerator.CandidateCombo> combos =
                new CandidateGenerator().generate(input, CandidateGenerator.Params.defaults());

        assertEquals(1, combos.size(), "一台正常机 + 一个正常巢应生成 1 个候选");
        assertEquals("UAV-OK", combos.get(0).droneId());
    }

    // ───────────── 机型串语义 ─────────────

    @Test
    @DisplayName("机型串 null/空白/只有逗号 一律归一为空列表（未登记＝不限制机型）")
    void blankModelsNormalizeToEmptyList() {
        for (String s : new String[] {null, "", "   ", ",", " , , "}) {
            assertTrue(CandidateInputAssembler.splitModels(s).isEmpty(),
                    "输入 [" + s + "] 应归一为空列表，实际："
                            + CandidateInputAssembler.splitModels(s));
        }
    }

    @Test
    @DisplayName("机型串 trim 且滤掉空项（HIGH.7 / MEDIUM.18）")
    void modelsAreTrimmedAndBlanksRemoved() {
        assertEquals(List.of("M30T", "M400"),
                CandidateInputAssembler.splitModels(" M30T ,, M400 , "));
    }
}
