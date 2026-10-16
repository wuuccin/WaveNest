// 模块三 动态会面与路径规划（罗才辉为主）。
// 只依赖 algo/contract/storage；不依赖 m2——预测快照由本模块的
// TrajectorySnapshotPort 端口反转获得，m3 全程不知道 m2 存在。
// 作用域：仅 algo 用 api（其类型出现在本模块对外签名）；其余一律 implementation，
// 不向消费者传递编译面——当前 src 未用 contract/storage/web-common，保留声明为并行线装配备用。
dependencies {
    api(project(":wl-algo"))
    implementation(project(":wl-contract"))
    implementation(project(":wl-storage"))
    implementation(libs.bundles.web.common)
}
