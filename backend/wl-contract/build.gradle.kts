// ★ 契约唯一权威：22 的 MQ/REST/WS 对象 + 23 状态枚举 + 主题名常量。
// 全组只读，禁止私自改字段；发现缺口报组长裁决。
dependencies {
    api(project(":wl-common"))
    // 契约枚举的中文取值上线（《1112》§3.4）：@JsonValue 与 @JsonIgnore 只依赖注解包
    api(libs.jackson.annotations)
}
