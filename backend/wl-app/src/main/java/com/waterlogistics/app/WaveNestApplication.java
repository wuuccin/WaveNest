package com.waterlogistics.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WaveNest 组装层启动类。
 *
 * <p>本类所在模块只做组装：启动、配置、Bean 装配（含端口接线
 * {@code TrajectorySnapshotPort} ← m2 实现）。业务逻辑一律落在
 * wl-m1~m4 与 wl-algo 内，本模块不含任何业务判断。
 */
@SpringBootApplication(scanBasePackages = "com.waterlogistics")
// Mapper 接口在 wl-storage 的 storage.repository 包，不在主类包之下，
// starter 只自动扫主类包，必须显式 @MapperScan（Bean 装配职责）。
@MapperScan("com.waterlogistics.storage.repository")
public class WaveNestApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaveNestApplication.class, args);
    }
}