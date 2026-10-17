package com.waterlogistics.app;

import com.waterlogistics.dispatch.reschedule.RescheduleService;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 动态会面接线端到端演示（test 分支专用，勿入 develop）。
 *
 * <p>与 RendezvousCoordinatorTest 的区别：那个用桩件验证输入装配与失败语义，
 * 本类不设任何桩，走真实 Spring 上下文 + 真实 MySQL + 真实 M3 求解器，
 * 用来回答一个问题：接线到底通不通。
 *
 * <p>前置：演示数据已灌（nest 9 / drone 14 / order_task 含 DEMO-ORD-01）；
 * 船侧快照需等 M1 仿真累积样本（calMinSamples=30、calDepth=20），故先轮询等待。
 */
@SpringBootTest
class RendezvousDemoTest {

    @Autowired
    private RescheduleService reschedule;

    @Autowired
    private OrderTaskMapper orderTaskMapper;

    @Autowired
    private TrajectorySnapshotPort snapshotPort;

    @Test
    @DisplayName("动态会面端到端：选机 -> 会面求解 -> 回填会面点")
    void demo_shouldSolveRendezvousAndBackfillMeetPoint() {
        String orderId = "DEMO-ORD-01";

        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        Assertions.assertNotNull(order, "演示订单必须已灌入：" + orderId);

        waitForSnapshot(order.getShipMmsi());

        String droneId = reschedule.reschedule(orderId);

        OrderTaskEntity after = orderTaskMapper.selectById(orderId);
        System.out.println("========== 动态会面演示结果 ==========");
        System.out.println("订单         : " + orderId + "  载重 " + after.getGoodsWeight()
                + "kg  优先级 " + after.getPriority());
        System.out.println("选中无人机   : " + droneId);
        System.out.println("会面点       : " + after.getMeetLon() + ", " + after.getMeetLat());
        System.out.println("起飞/会面时刻 : " + after.getMeetTime());
        System.out.println("=====================================");

        Assertions.assertNotNull(droneId, "应选出候选无人机");
        Assertions.assertNotNull(after.getMeetLon(), "会面点经度必须由算法层回填");
        Assertions.assertNotNull(after.getMeetLat(), "会面点纬度必须由算法层回填");
        Assertions.assertNotNull(after.getMeetTime(), "起飞/会面时刻必须由算法层回填");
    }

    /**
     * 等船侧预测快照就绪。
     *
     * <p>为什么必须等：M1 仿真每 1s 一条 AIS，而预测要先过 calMinSamples（30）
     * 与 calDepth（20）两道校准门槛，启动头几十秒内 latest() 返回 null 是正常的，
     * 不是接线出错——这点务必分清，否则会把「等样本」误判成「求解坏了」。
     */
    private void waitForSnapshot(String shipMmsi) {
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            if (snapshotPort.latest(shipMmsi) != null) {
                return;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assertions.fail("90 秒内船 " + shipMmsi + " 仍未产生预测快照："
                + "M1 仿真是否在发 AIS？sys_param 的 calMinSamples 是否过大？");
    }
}