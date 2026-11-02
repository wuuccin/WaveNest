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

    /**
     * 写回一个参数（键不存在则插入），并<b>只更新本键</b>的缓存——不整表 refresh。
     *
     * <p><b>为什么必须同时写缓存</b>：{@link #get} 系列读的是 {@link #cache}，不是库。
     * 只写库不更缓存，本进程内后续 {@code getDouble} 仍返回旧值，「回写成功」就成了假象；
     * 而整表 {@link #refresh} 在高频写回路径上会把全表重载一遍，代价与影响面都不必要。
     *
     * <p><b>为什么要判空值</b>：{@code param_value} 在 DDL 里是 {@code NOT NULL}，
     * 传 null 会以 DataIntegrityViolation 的形式炸在调用方（通常是校准链路的后台线程），
     * 那里的异常没人接。提前拒掉并给出键名，故障点才明确。
     *
     * @param key         参数键（camelCase，见《37》§5.14.1）
     * @param value       参数值（字符串形态，与库内存储一致）
     * @param category    分类；为 null 时不覆盖既有行的分类（新行则该列为 null）
     * @param description 说明；为 null 时不覆盖既有行的说明
     * @return 是否写入成功（键或值非法时返回 false，调用方据此决定是否记警告）
     */
    public synchronized boolean put(String key, String value, String category, String description) {
        if (key == null || key.isBlank() || value == null) {
            return false;
        }
        SysParamEntity existing = sysParamMapper.selectById(key);
        SysParamEntity row = existing == null ? new SysParamEntity() : existing;
        row.setParamKey(key);
        row.setParamValue(value);
        if (category != null) {
            row.setCategory(category);
        }
        if (description != null) {
            row.setDescription(description);
        }
        if (existing == null) {
            sysParamMapper.insert(row);
        } else {
            sysParamMapper.updateById(row);
        }
        cache.put(key, value);
        return true;
    }
}
