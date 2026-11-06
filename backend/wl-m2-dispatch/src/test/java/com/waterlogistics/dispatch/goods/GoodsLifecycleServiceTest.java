package com.waterlogistics.dispatch.goods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import java.lang.reflect.Proxy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GoodsLifecycleServiceTest {
    @BeforeAll
    static void init() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "goods-lifecycle-test"),
                GoodsEntity.class);
    }

    @Test
    void readyGoodsLoadOnlyOnce() {
        State state = new State(GoodsStatus.READY);
        GoodsLifecycleService service = new GoodsLifecycleService(mapper(state));
        service.load("O1", "D1");
        assertEquals(GoodsStatus.LOADED, state.status);
        assertThrows(IllegalStateException.class, () -> service.load("O1", "D1"));
    }

    @Test
    void loadedGoodsDeliveredOnlyOnce() {
        State state = new State(GoodsStatus.LOADED);
        GoodsLifecycleService service = new GoodsLifecycleService(mapper(state));
        service.deliver("O1", "D1");
        assertEquals(GoodsStatus.DELIVERED, state.status);
        assertThrows(IllegalStateException.class,
                () -> service.recycle("O1", "G1", "D1", "N1"));
    }

    @Test
    void loadedGoodsRecycledOnlyAtNestAndCannotBeDelivered() {
        State state = new State(GoodsStatus.LOADED);
        GoodsLifecycleService service = new GoodsLifecycleService(mapper(state));
        assertThrows(IllegalStateException.class,
                () -> service.recycle("O1", "G1", "D1", ""));
        service.recycle("O1", "G1", "D1", "N1");
        assertEquals(GoodsStatus.RECYCLED, state.status);
        assertThrows(IllegalStateException.class, () -> service.deliver("O1", "D1"));
    }

    private static GoodsMapper mapper(State state) {
        return (GoodsMapper) Proxy.newProxyInstance(GoodsMapper.class.getClassLoader(),
                new Class<?>[]{GoodsMapper.class}, (proxy, method, args) -> {
                    if (!"update".equals(method.getName())) {
                        return null;
                    }
                    @SuppressWarnings("unchecked")
                    LambdaUpdateWrapper<GoodsEntity> update = (LambdaUpdateWrapper<GoodsEntity>) args[1];
                    String sql = update.getSqlSegment();
                    var values = update.getParamNameValuePairs().values();
                    GoodsStatus previous = state.status;
                    if (!sql.contains("status") || !values.contains(previous.getValue())
                            || !values.contains("O1")) {
                        return 0;
                    }
                    if (previous == GoodsStatus.READY && values.contains(GoodsStatus.LOADED.getValue())) {
                        state.status = GoodsStatus.LOADED;
                        return 1;
                    }
                    if (previous == GoodsStatus.LOADED && values.contains(GoodsStatus.DELIVERED.getValue())) {
                        state.status = GoodsStatus.DELIVERED;
                        return 1;
                    }
                    if (previous == GoodsStatus.LOADED && values.contains(GoodsStatus.RECYCLED.getValue())) {
                        state.status = GoodsStatus.RECYCLED;
                        return 1;
                    }
                    return 0;
                });
    }

    private static final class State {
        private GoodsStatus status;
        private State(GoodsStatus status) { this.status = status; }
    }
}
