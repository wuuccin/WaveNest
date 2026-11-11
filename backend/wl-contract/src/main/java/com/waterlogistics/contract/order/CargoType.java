package com.waterlogistics.contract.order;

import java.util.Arrays;
import java.util.List;

/**
 * 演示系统认识的配送品类默认全集。
 *
 * <p>线上传输一律使用 {@link #getValue()} 返回的中文取值，与既有 {@code goodsType}
 * 请求字段保持一致。枚举名只供 Java 代码引用，不作为传输编码。
 *
 * <p>本枚举定义系统认识哪些品类。当前允许配送哪些品类由
 * {@code wavenest.m2.admission.cargo-whitelist} 配置决定，两者不得混为一谈。
 * 声明顺序就是字典接口与拒单提示的固定输出顺序。
 */
public enum CargoType {

    /** 医疗物资 */
    MEDICAL_SUPPLIES("医疗物资"),

    /** 应急药品 */
    EMERGENCY_MEDICINE("应急药品"),

    /** 食品 */
    FOOD("食品"),

    /** 生活物资 */
    DAILY_SUPPLIES("生活物资");

    private final String value;

    CargoType(String value) {
        this.value = value;
    }

    /** 返回线上传输用的中文品类值 */
    public String getValue() {
        return value;
    }

    /** 返回按声明顺序排列的默认全集 */
    public static List<String> defaultValues() {
        return Arrays.stream(values()).map(CargoType::getValue).toList();
    }

    /** 判断某个中文取值是否属于系统认识的品类 */
    public static boolean contains(String value) {
        for (CargoType type : values()) {
            if (type.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按中文取值解析枚举。
     *
     * @throws IllegalArgumentException 传入取值不在默认全集之内时抛出
     */
    public static CargoType fromValue(String value) {
        for (CargoType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知配送品类：" + value);
    }
}
