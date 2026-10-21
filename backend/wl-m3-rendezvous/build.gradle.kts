// 模块三 动态会面与路径规划（罗才辉为主）。
// 只依赖 algo；预测快照由本模块的 TrajectorySnapshotPort 端口反转获得，
// m3 全程不知道 m2 存在。对外 facade 直接暴露 algo 模型类型（会面点解、可飞航迹、
// 快照视图等），消费者须自行声明 algo 依赖；本模块不把 algo 的全部实现面传递出去。
dependencies {
    implementation(project(":wl-algo"))
    // 值对象与异常的 getter 用 @Getter；仅编译期需要，不进运行时 classpath（同 wl-storage 实体口径）。
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
