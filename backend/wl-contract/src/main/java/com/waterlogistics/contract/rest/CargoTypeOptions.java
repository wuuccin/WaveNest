package com.waterlogistics.contract.rest;

import java.util.List;

/**
 * 配送品类字典查询响应。
 *
 * <p>本响应只返回当前生效的可承运品类，不返回系统认识的默认全集。
 * 列表顺序与接单判定、拒单提示使用的顺序一致。
 *
 * @param cargoTypes 当前生效的可承运品类，按固定顺序排列
 */
public record CargoTypeOptions(List<String> cargoTypes) {

    public CargoTypeOptions {
        cargoTypes = List.copyOf(cargoTypes);
    }
}
