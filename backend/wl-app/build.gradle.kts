// wl-app 组装层（收口方维护）。
// 只做三件事：启动类、application.yml、Bean 装配（含端口接线
// TrajectorySnapshotPort ← m2 实现）。★ 不含任何业务逻辑（包结构设计§三）。
// app/push/ 是后端唯一实时出口（WS/SSE 帧扇出，纯转发无业务判断）。
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":wl-m1-collector"))
    implementation(project(":wl-m2-dispatch"))
    implementation(project(":wl-m3-rendezvous"))
    implementation(project(":wl-m4-monitor"))
    implementation(project(":wl-storage"))
    implementation(project(":wl-algo"))

    // 后端唯一实时出口：WebSocket 服务端
    implementation(libs.spring.boot.starter.websocket)

    // JDBC 驱动只在组装层需要，业务模块只经 wl-storage 访问数据库
    runtimeOnly(libs.mysql.connector.j)

    annotationProcessor(libs.spring.boot.configuration.processor)

    // 冒烟/集成测试（版本由 Spring Boot BOM 提供，模块内不写版本号）
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(libs.mysql.connector.j)
}