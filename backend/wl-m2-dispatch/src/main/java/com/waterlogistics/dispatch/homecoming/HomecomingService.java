package com.waterlogistics.dispatch.homecoming;

import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingReview;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.inflight.NestCandidate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 返航复核与归巢资源判定——模块层编排，落款《包结构设计》行 84
 * 「<b>homecoming/  # 返航复核与归巢资源判定（吴）：调 algo.inflight 三级判定</b>」，属 wl-m2-dispatch。
 *
 * <p><b>本类只做编排，不做判定</b>：三级降级逻辑全在 {@link HomecomingReview}（算法层）。
 * 照《84》§4.2 输出表，本类负责把结论送到三处下游：
 * <ul>
 *   <li><b>B 组</b>：三级落点结论 → 生成返航航迹 ＋ 精算返程电量</li>
 *   <li><b>D 组</b>：占位请求，<b>仅第二级</b>（needSlotRequest=true）时发出</li>
 *   <li><b>模块四</b>：返航执行指令</li>
 * </ul>
 *
 * <p><b>《10-16 当日任务清单》§3.1 对本项的要求是「简化三级，留接口」</b>——
 * 故下游三处均以 TODO 标注签名，不在本轮接实（依赖 B 组返航精算与 D 组占位回执）。
 * 本类<b>不落子</b>（《84》§4.3「不做占位落子——归 D 组」）、<b>不改投</b>（原巢唯一化）。
 */
public class HomecomingService {

    private static final Logger log = LoggerFactory.getLogger(HomecomingService.class);

    /**
     * 返航复核：判三级落点并给出结论。
     *
     * @param input      功能10 入口输入快照
     * @param params     阈值与判据参数，<b>须由本层从 {@code sys_param} 读出并按《84》§4.3.1
     *                   的换算关系换算后注入</b>（比率→百分点、秒→毫秒），算法层不读库、不硬编码
     * @param candidates 候选他巢台账，<b>须由本层从 D 组 {@code occupancy_slot} 读出后注入</b>
     * @return 三级落点结论（含候选他巢逐项打叉审计记录）
     */
    public HomecomingDecision reviewAndDecide(HomecomingInput input, HomecomingParams params,
            List<NestCandidate> candidates) {
        HomecomingDecision decision = HomecomingReview.review(input, params, candidates);
        dispatch(decision);
        return decision;
    }

    /**
     * 按结论分发到下游（《84》§4.2 输出表三行）。
     *
     * <p>本轮只留结构不接实：三个下游各自依赖他组今日产出，接了反而会卡编译。
     * 拆成三个方法是为了让<b>每行输出各自对应一条去路</b>，接线时逐个填、不必再拆。
     *
     * <p>分发前的落点结论落一条 INFO 日志（含候选审计条数与风险标记）——
     * 答辩要展示的「候选表逐项打叉」，这条日志就是线上可回溯的入口。
     */
    private void dispatch(HomecomingDecision decision) {
        log.info("返航落点结论：tier={} targetId={} targetPos={} 占位请求={} 候选审计={}条 风险标记={} 判定说明={}",
                decision.tier().getValue(), decision.targetId(), decision.targetPosition(),
                decision.needSlotRequest(), decision.candidateAudit().size(),
                decision.riskFlags(), decision.reason());
        toRoutePlannerAndEnergy(decision);
        toSlotOwner(decision);
        toFlightController(decision);
    }

    /** ① 落点结论 → **B 组**：生成返航航迹 ＋ 精算返程电量（B 不得自行改点，见《84》E-3） */
    private void toRoutePlannerAndEnergy(HomecomingDecision decision) {
        // TODO(16 号接链)：交 B 组；同时落 task_log（张睿哲持久化）审计记录。
        // 第三级目标标识待本组 L4 迫降点层补齐（《84》§7.6 第 7 步）后再交，不得由 B 组代出。
        if (decision.tier() == HomecomingTier.EMERGENCY_LAND_FIELD && decision.targetId() == null) {
            log.warn("第三级迫降野外但无迫降点标识：L4 迫降点层未接台账，出点责任在本组不在 B 组");
        }
    }

    /** ② 占位请求 → **D 组**：<b>仅第二级发出</b>（《84》§4.2「占位请求仅迫停他巢时」） */
    private void toSlotOwner(HomecomingDecision decision) {
        // TODO(16 号接链)：needSlotRequest 为真时发起归巢时隙占位请求，收占位回执；
        // 占位即锁定（B4 单向不可逆），锁定后不得回切上一级。
        if (decision.needSlotRequest()) {
            log.info("待发 D 组归巢时隙占位请求：nestId={}", decision.targetId());
        }
    }

    /** ③ 返航执行指令 → **模块四（M4）** */
    private void toFlightController(HomecomingDecision decision) {
        // TODO(16 号接链)：下发返航执行指令（离散指令按 `34` 编码）。
        log.debug("待下发模块四返航执行指令：tier={}", decision.tier().getValue());
    }
}