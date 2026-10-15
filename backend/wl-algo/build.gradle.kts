// ★ 算法层：A–G 七组纯计算，无 Spring、无 DB（包结构设计§二铁律 4）。
// 因此这里只能依赖 wl-contract 的对象，不引入任何 Spring starter。
// 阈值与台账参数由调用方注入，禁止硬编码。
dependencies {
    api(project(":wl-contract"))
}
