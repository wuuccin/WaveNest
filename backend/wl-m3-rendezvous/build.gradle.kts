// 模块三 动态会面与路径规划（罗才辉为主）。
// 只依赖 algo/contract/storage；不依赖 m2——预测快照由本模块的
// TrajectorySnapshotPort 端口反转获得，m3 全程不知道 m2 存在。
dependencies {
    api(project(":wl-contract"))
    api(project(":wl-algo"))
    api(project(":wl-storage"))
    api(libs.bundles.web.common)
}
