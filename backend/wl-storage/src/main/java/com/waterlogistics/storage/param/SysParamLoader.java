package com.waterlogistics.storage.param;

import com.waterlogistics.storage.entity.SysParamEntity;
import com.waterlogistics.storage.repository.SysParamMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sys_param 集中加载（37 §5.14.1）。
 * 业务方只读、不硬编码；任何阈值/窗口/容量一律经本类读取。
 * 键名 camelCase，见 37 §5.14.1。
 */
@Service
public class SysParamLoader implements InitializingBean {

    private final SysParamMapper sysParamMapper;

    /** 全量参数缓存（键名 → 值，字符串） */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SysParamLoader(SysParamMapper sysParamMapper) {
        this.sysParamMapper = sysParamMapper;
    }

    @Override
    public void afterPropertiesSet() {
        refresh();
    }

    /** 全量刷新（重载 sys_param 所有行） */
    public void refresh() {
        cache.clear();
        for (SysParamEntity p : sysParamMapper.selectList(null)) {
            cache.put(p.getParamKey(), p.getParamValue());
        }
    }

    /** 读取字符串值；无此键返回 null */
    public String get(String key) {
        return cache.get(key);
    }

    /** 读取字符串值；无此键返回默认值 */
    public String get(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    /** 读取整数值；无此键或解析失败返回默认值 */
    public int getInt(String key, int defaultValue) {
        String v = cache.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 读取浮点值；无此键或解析失败返回默认值 */
    public double getDouble(String key, double defaultValue) {
        String v = cache.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 只读快照（不可变） */
    public Map<String, String> all() {
        return Map.copyOf(cache);
    }
}
