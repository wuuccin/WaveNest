// 模块三 动态会面与路径规划（罗才辉为主）。
// 只依赖 algo/contract/storage；不依赖 m2——预测快照由本模块的
// TrajectorySnapshotPort 端口反转获得，m3 全程不知道 m2 存在。
// 作用域：仅 algo 用 api——其类型出现在本模块对外签名；注意 algo 自身以 api 声明 contract，
// 故 contract 类型会随 algo 传递至消费者，属预期。其余一律 implementation（当前 src 未使用
// contract/storage/web-common，经确认保留声明、为并行线装配备用），不进消费者编译面。
dependencies {
    api(project(":wl-algo"))
    implementation(project(":wl-contract"))
    implementation(project(":wl-storage"))
    implementation(libs.bundles.web.common)
    // 值对象与异常的 getter 用 @Getter；仅编译期需要，不进运行时 classpath（同 wl-storage 实体口径）。
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
