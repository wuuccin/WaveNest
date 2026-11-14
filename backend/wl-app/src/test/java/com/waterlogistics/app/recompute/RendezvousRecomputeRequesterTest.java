package com.waterlogistics.app.recompute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.NoSolutionReason;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSnapshot;
import com.waterlogistics.dispatch.prediction.RecomputeDecisionGate;
import com.waterlogistics.dispatch.prediction.RecomputeRequest;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.dispatch.weather.WeatherSnapshotPort;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException;
import com.waterlogistics.rendezvous.solver.RecalcOptions;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcRequest;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcResult;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 六a 重算承接的单测：航向跳变信号经 {@link RendezvousFacade#recompute} 重解会面点，
 * 不经过 M2 的会面编排；失败与无解一律保留旧点。
 */
class RendezvousRecomputeRequesterTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String MMSI = "123456789";
    private static final String DRONE = "UAV-01";
    private static final String ORDER_ID = "ORD-1";

    private PredictionService predictionService;
    private OrderTaskMapper orderTaskMapper;
    private RendezvousFacade rendezvousFacade;
    private DroneContextPort droneContextPort;
    private WeatherSnapshotPort weatherSnapshotPort;
    private SysParamLoader sysParamLoader;
    private RecomputeDecisionGate decisionGate;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactionTemplate;
    private RendezvousRecomputeRequester requester;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderTaskEntity.class);
    }

    @BeforeEach
    void setUp() {
        predictionService = mock(PredictionService.class);
        orderTaskMapper = mock(OrderTaskMapper.class);
        rendezvousFacade = mock(RendezvousFacade.class);
        droneContextPort = mock(DroneContextPort.class);
        weatherSnapshotPort = mock(WeatherSnapshotPort.class);
        sysParamLoader = mock(SysParamLoader.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        transactionTemplate = mock(TransactionTemplate.class);

        decisionGate = new RecomputeDecisionGate(sysParamLoader);
        requester = new RendezvousRecomputeRequester(predictionService, orderTaskMapper,
                rendezvousFacade, droneContextPort, weatherSnapshotPort, sysParamLoader,
                decisionGate, eventPublisher, transactionTemplate);

        lenient().when(sysParamLoader.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(sysParamLoader.getDouble(anyString(), anyDouble()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(weatherSnapshotPort.latest(anyDouble(), anyDouble(), anyLong()))
                .thenReturn(null);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void headingJump_shouldRecomputeThroughFacadeAndCommitMeetPoint() {
        when(predictionService.recompute(MMSI)).thenReturn(Optional.of(snapshot()));
        when(orderTaskMapper.selectList(any())).thenReturn(List.of(order()));
        when(orderTaskMapper.update(isNull(), any())).thenReturn(1);
        when(droneContextPort.resolve(DRONE)).thenReturn(droneContext());
        when(rendezvousFacade.recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class))).thenReturn(solvedResult());

        requester.requestRecompute(request());

        ArgumentCaptor<RendezvousRecalcRequest> captor =
                ArgumentCaptor.forClass(RendezvousRecalcRequest.class);
        verify(rendezvousFacade).recompute(captor.capture(), any(RecalcOptions.class));
        RendezvousRecalcRequest recalc = captor.getValue();
        assertEquals(DRONE, recalc.droneId());
        assertEquals("2", recalc.snapshotVersion(), "应以刷新后的快照版本为一致性锚");
        assertEquals(120.0, recalc.stateSnapshot().shipCogDeg(), 1e-9,
                "重算定向必须取观测航向，不取快照主轴方位");
        assertEquals(115.86, recalc.stateSnapshot().shipLon(), 1e-9);
        assertEquals(28.66, recalc.stateSnapshot().shipLat(), 1e-9);
        assertTrue(recalc.stateSnapshot().sogMs() > 0.0, "船速应由预测点列派生");
        verify(orderTaskMapper).update(isNull(), any());
        verify(eventPublisher).publishEvent(any(RendezvousPlanCommittedEvent.class));
    }

    @Test
    void noSolution_shouldKeepOldMeetPoint() {
        when(predictionService.recompute(MMSI)).thenReturn(Optional.of(snapshot()));
        when(orderTaskMapper.selectList(any())).thenReturn(List.of(order()));
        when(droneContextPort.resolve(DRONE)).thenReturn(droneContext());
        when(rendezvousFacade.recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class))).thenReturn(unsolvedResult());

        requester.requestRecompute(request());

        verify(rendezvousFacade).recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class));
        verify(orderTaskMapper, never()).update(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void observedCog360_shouldFallbackToSnapshotCourseAndPropagateReason() {
        when(predictionService.recompute(MMSI)).thenReturn(Optional.of(snapshot()));
        when(orderTaskMapper.selectList(any())).thenReturn(List.of(order()));
        when(orderTaskMapper.update(isNull(), any())).thenReturn(1);
        when(droneContextPort.resolve(DRONE)).thenReturn(droneContext());
        when(rendezvousFacade.recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class))).thenReturn(solvedResult());

        requester.requestRecompute(new RecomputeRequest(
                MMSI, 1L, 80.0, 360.0, 40.0, "观测航向跳变", NOW));

        ArgumentCaptor<RendezvousRecalcRequest> captor =
                ArgumentCaptor.forClass(RendezvousRecalcRequest.class);
        verify(rendezvousFacade).recompute(captor.capture(), any(RecalcOptions.class));
        assertEquals(90.0, captor.getValue().stateSnapshot().shipCogDeg(), 1e-9,
                "360 是 AIS 未知哨兵，应回落快照主轴方位而不是交给下游拒绝");
        assertEquals("观测航向跳变", captor.getValue().triggerReason(),
                "触发原因应透传，不得写死");
    }

    @Test
    void windDirectionOutOfRange_shouldBeNormalized() {
        when(predictionService.recompute(MMSI)).thenReturn(Optional.of(snapshot()));
        when(orderTaskMapper.selectList(any())).thenReturn(List.of(order()));
        when(orderTaskMapper.update(isNull(), any())).thenReturn(1);
        when(droneContextPort.resolve(DRONE)).thenReturn(droneContext());
        when(rendezvousFacade.recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class))).thenReturn(solvedResult());
        when(sysParamLoader.getDouble("windDirDeg", 0.0)).thenReturn(400.0);

        requester.requestRecompute(request());

        ArgumentCaptor<RecalcOptions> captor = ArgumentCaptor.forClass(RecalcOptions.class);
        verify(rendezvousFacade).recompute(any(RendezvousRecalcRequest.class), captor.capture());
        assertEquals(40.0, captor.getValue().representativeWind().dirDeg(), 1e-9,
                "风向越界应归一，不应让 Wind 构造抛异常");
    }

    @Test
    void refreshFailure_shouldNotSkipRemainingOrders() {
        when(predictionService.recompute(MMSI))
                .thenReturn(Optional.of(snapshot()))
                .thenThrow(new IllegalArgumentException("观测越界"));
        when(orderTaskMapper.selectList(any())).thenReturn(List.of(order()));
        when(droneContextPort.resolve(DRONE)).thenReturn(droneContext());
        when(rendezvousFacade.recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class)))
                .thenThrow(new SnapshotVersionMismatchException(MMSI, "2", "3"));

        requester.requestRecompute(request());

        verify(orderTaskMapper, never()).update(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deterministicValidationFailure_shouldNotRetry() {
        when(predictionService.recompute(MMSI)).thenReturn(Optional.of(snapshot()));
        when(orderTaskMapper.selectList(any())).thenReturn(List.of(order()));
        when(droneContextPort.resolve(DRONE)).thenReturn(droneContext());
        when(rendezvousFacade.recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class)))
                .thenThrow(new IllegalArgumentException("船状态时刻超出快照覆盖范围"));

        requester.requestRecompute(request());

        verify(rendezvousFacade, times(1)).recompute(any(RendezvousRecalcRequest.class),
                any(RecalcOptions.class));
        verify(orderTaskMapper, never()).update(any(), any());
    }

    private static RecomputeRequest request() {
        return new RecomputeRequest(MMSI, 1L, 80.0, 120.0, 40.0, "航向跳变", NOW);
    }

    private static PredictionSnapshot snapshot() {
        return new PredictionSnapshot(MMSI, 2L, NOW, 90.0, 900L, "VALID",
                List.of(new ShipTrajPoint(NOW, 115.86, 28.66, 10.0, 5.0),
                        new ShipTrajPoint(NOW + 60_000L, 115.861, 28.66, 10.0, 5.0)),
                null, null, null, null, null);
    }

    private static OrderTaskEntity order() {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId(ORDER_ID);
        order.setShipMmsi(MMSI);
        order.setDroneId(DRONE);
        order.setStatus(OrderStatus.OUTBOUND.getValue());
        order.setMeetLon(BigDecimal.valueOf(115.86));
        order.setMeetLat(BigDecimal.valueOf(28.66));
        return order;
    }

    private static DroneContext droneContext() {
        return new DroneContext(DRONE, "NEST-01", "NEST-01", 115.85, 28.65, 12.0, null);
    }

    private static RendezvousRecalcResult solvedResult() {
        B_RendezvousSolution solution = B_RendezvousSolution.solved(
                "SOL-1", null, 115.87, 28.67,
                NOW + 100_000L, NOW + 220_000L, NOW + 100_000L, 100, "2");
        return new RendezvousRecalcResult("RECALC-" + ORDER_ID, solution);
    }

    private static RendezvousRecalcResult unsolvedResult() {
        B_RendezvousSolution solution = B_RendezvousSolution.unsolvable(
                "SOL-1", null, NoSolutionReason.CANNOT_CATCH, "2");
        return new RendezvousRecalcResult("RECALC-" + ORDER_ID, solution);
    }
}
