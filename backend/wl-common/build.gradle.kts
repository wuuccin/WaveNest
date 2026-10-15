// 通用底座：工具、统一异常、Result 封装、坐标与单位归一。
// 依赖方向：本模块不依赖任何其他 wl-* 模块（处在依赖链最底）。
dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
