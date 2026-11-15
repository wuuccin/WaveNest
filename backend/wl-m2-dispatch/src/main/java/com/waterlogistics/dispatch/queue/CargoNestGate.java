package com.waterlogistics.dispatch.queue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.inflight.TimeWindow;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.GoodsMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 货到巢关（11-05 序号 2 · 张睿哲）：派单前的「锁巢时隙 + 货物就绪」判定。
 *
 * <p>语义（11-05 任务清单 §三张睿哲段）：
 * <ol>
 *   <li>派单前先 {@link OccupancySlotService#tryReserve} 锁货巢时隙，
 *       货未就绪则订单转「已受理待唤醒 + WAIT_CARGO」持锁等货；</li>
 *   <li>货就绪（cargo-ready）后放行转「待调度」（由 {@link CargoGateWaker} 轮询驱动）；</li>
 *   <li>锁超时（{@code sys_param.cargoLockTimeoutSec}）转 WAIT_RESOURCE；</li>
 *   <li>终态按 order_id 释放（既有 releaseByOrder 链路，本类不管）。</li>
 * </ol>
 *
 * <p><b>订单没有货巢时不经本关</b>，直接放行。货巢来源有两种：客户端显式指定，
 * 或系统在下单时刻决策；两者都落进 {@code order_task.cargo_nest_id}，
 * 门关只认订单上有没有货巢，不区分来源。系统未选定货巢（决策开关关闭或选不出可用巢）
 * 与客户端未填是同一种情形：订单不经门关，按旧口径处理。</p>
 *
 * <p>锁幂等：同一订单重复评估（首派、唤醒重派）时，
 * 已有有效占位则复用，不重复 tryReserve——否则本单自己的锁会占住容量。</p>
 */
@Component
public class CargoNestGate {

    private static final Logger log = LoggerFactory.getLogger(CargoNestGate.class);

    /** 锁超时秒数参数键（012_cargo_gate_params.sql 登记） */
    public static final String LOCK_TIMEOUT_KEY = "cargoLockTimeoutSec";
    /** 锁超时兜底值（秒）：参数缺失时 300s */
    public static final int DEFAULT_LOCK_TIMEOUT_SEC = 300;

    /** 货物就绪状态词（001_init.sql §5.3 与 CargoReadyService 口径一致） */
    private static final String GOODS_STATUS_READY = "已就绪";

    private final GoodsMapper goodsMapper;
    private final OccupancySlotService occupancySlotService;
    private final SysParamLoader sysParamLoader;

    public CargoNestGate(GoodsMapper goodsMapper, OccupancySlotService occupancySlotService,
            SysParamLoader sysParamLoader) {
        this.goodsMapper = goodsMapper;
        this.occupancySlotService = occupancySlotService;
        this.sysParamLoader = sysParamLoader;
    }

    /** 门关结论。 */
    public enum Decision {
        /** 货已就绪且巢时隙已锁定：继续派单 */
        PROCEED,
        /** 货未到巢：已锁巢时隙，转「已受理待唤醒 + WAIT_CARGO」等货 */
        WAIT_CARGO,
        /** 巢时隙锁不到（容量满/巢不可用）：转「已受理待唤醒 + WAIT_RESOURCE」 */
        WAIT_RESOURCE
    }

    /**
     * 评估订单能否过货到巢关。
     *
     * @param orderId     订单号
     * @param cargoNestId 货物所在巢；为空表示订单上没有货巢（系统未选定或客户端未填），
     *                    此时不启用门关，直接 {@link Decision#PROCEED}
     */
    public Decision evaluate(String orderId, String cargoNestId) {
        if (cargoNestId == null || cargoNestId.isBlank()) {
            return Decision.PROCEED;
        }
        boolean ready = isCargoReady(orderId, cargoNestId);
        boolean locked = occupancySlotService.findActiveSlotByOrder(orderId).isPresent();
        if (!locked) {
            long nowMs = System.currentTimeMillis();
            long timeoutMs = lockTimeoutSec() * 1000L;
            locked = occupancySlotService.tryReserve(cargoNestId,
                    new TimeWindow(nowMs, nowMs + timeoutMs), orderId).isPresent();
        }
        if (ready) {
            if (locked) {
                log.info("货到巢关放行：orderId={} cargoNestId={}（货已就绪，时隙已锁）", orderId, cargoNestId);
                return Decision.PROCEED;
            }
            log.info("货到巢关转资源等待：orderId={} cargoNestId={}（货已就绪但巢时隙锁不到）",
                    orderId, cargoNestId);
            return Decision.WAIT_RESOURCE;
        }
        if (locked) {
            log.info("货到巢关待货：orderId={} cargoNestId={}（货未到巢，时隙已锁，等 cargo-ready）",
                    orderId, cargoNestId);
            return Decision.WAIT_CARGO;
        }
        log.info("货到巢关转资源等待：orderId={} cargoNestId={}（货未就绪且巢时隙锁不到）",
                orderId, cargoNestId);
        return Decision.WAIT_RESOURCE;
    }

    /**
     * 货物是否已到巢就绪：存在该订单的 goods 行，且 {@code status='已就绪'}
     * 且 {@code location} 等于货巢（与 AdmissionInputAssembler 的 goodsReady 口径一致）。
     *
     * <p><b>D-01</b>：空货巢分支返回 true——未指定货巢的订单不经过货到巢关
     * （{@link #evaluate} 已短路），其货物在建行时即置「已就绪」，此处照实反映。
     * 调用点 {@code CargoGateWaker} 拿到的结论与门关一致，不会出现「门关放行、
     * 唤醒器仍判未就绪」的双口径。
     */
    public boolean isCargoReady(String orderId, String cargoNestId) {
        if (cargoNestId == null || cargoNestId.isBlank()) {
            return true;
        }
        List<GoodsEntity> goods = goodsMapper.selectList(new LambdaQueryWrapper<GoodsEntity>()
                .eq(GoodsEntity::getOrderId, orderId)
                .eq(GoodsEntity::getStatus, GOODS_STATUS_READY)
                .eq(GoodsEntity::getLocation, cargoNestId)
                .last("LIMIT 1"));
        return !goods.isEmpty();
    }

    /** 锁超时秒数（sys_param.cargoLockTimeoutSec，缺省 300）。 */
    public int lockTimeoutSec() {
        return sysParamLoader.getInt(LOCK_TIMEOUT_KEY, DEFAULT_LOCK_TIMEOUT_SEC);
    }

    /** 释放订单持有的巢时隙锁（锁超时处置用）；底层 releaseByOrder 幂等不抛异常。 */
    public void releaseLockQuietly(String orderId) {
        occupancySlotService.releaseByOrder(orderId);
    }
}
