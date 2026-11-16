package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.CargoType;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 配送品类有效集合的唯一解析入口。
 *
 * <p>系统认识的默认全集由 {@link CargoType} 定义。当前允许配送的显式子集由
 * {@code wavenest.m2.admission.cargo-whitelist} 配置给出。本类把配置解析成一份
 * 有序不可变集合，接单判定、字典查询接口与拒单提示共同消费这一份集合。
 *
 * <p>未知取值与显式空集合都会抛异常终止启动，本类不静默过滤，避免配置写错后
 * 系统带着空集合运行并拒绝全部订单。
 */
public final class CargoTypeCatalog {

    private CargoTypeCatalog() {
    }

    /** 返回按固定顺序排列的默认全集 */
    public static List<String> defaults() {
        return CargoType.defaultValues();
    }

    /**
     * 解析配置为当前生效的可承运集合。
     *
     * @param spec 逗号分隔的配置值，取 {@code null} 表示未配置并回落到默认全集
     * @return 有序不可变集合
     * @throws IllegalArgumentException 配置含未知取值，或显式配置为空集合时抛出
     */
    public static Set<String> resolve(String spec) {
        if (spec == null) {
            return orderedSet(defaults());
        }
        List<String> parts = Arrays.stream(spec.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
        if (parts.isEmpty()) {
            throw new IllegalArgumentException(
                    "可承运品类配置为空，系统不会静默运行：" + CargoType.defaultValues());
        }
        for (String part : parts) {
            if (!CargoType.contains(part)) {
                throw new IllegalArgumentException(
                        "未知配送品类：" + part + "；允许的取值为 " + CargoType.defaultValues());
            }
        }
        return orderedSet(new LinkedHashSet<>(parts));
    }

    /** 把有效集合按固定顺序拼接成面向用户的可选范围文案 */
    public static String displayText(Collection<String> effective) {
        return String.join("、", effective);
    }

    private static Set<String> orderedSet(Collection<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
