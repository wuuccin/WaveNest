// WaveNest 后端根构建脚本。
// 四条纪律（照《技术栈与运行环境》§六）：只用 Wrapper、只用 Kotlin DSL、
// 版本只写在 gradle/libs.versions.toml、构建脚本改动须全绿后再提交。
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

// Java 目标版本 release 17：根脚本统一设定，混版 JDK（17/21）编译产物一致。
allprojects {
    group = "com.waterlogistics"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // 只声明语言级，不锁定具体 JDK 发行版——全组 JDK 版本自选，
            // 具体路径由 $GRADLE_USER_HOME/gradle.properties 的 org.gradle.java.home 提供，
            // 严禁写进仓库（队友机器路径不同会构建失败）。
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -parameters 保留方法参数名，Spring 的构造注入与 Jackson 序列化依赖它
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}