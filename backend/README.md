# 后端工程结构与依赖规则

> 九个 Gradle 模块，模块边界＝职责边界。依赖单向无环。
> 结构与依赖方向照《包结构设计》§一/§二；技术版本照《技术栈与运行环境》§六。

## 一、模块清单与负责人

| Gradle 模块 | 包名 | 负责人 | 职责 |
|---|---|---|---|
| `wl-common` | `com.waterlogistics.common` | 吴可欣 | 通用工具、统一异常、Result、坐标与单位归一 |
| `wl-contract` | `com.waterlogistics.contract` | 吴可欣（**全组只读**） | ★ 契约唯一权威：MQ 主题与消息对象、REST 路径与对象、WS 帧、状态枚举 |
| `wl-algo` | `com.waterlogistics.algo` | A–G 七组 | ★ 算法层纯计算，**无 Spring、无 DB**，可独立单测 |
| `wl-storage` | `com.waterlogistics.storage` | 张睿哲 | 14 表实体与 Repository、Redis、sys_param 集中加载 |
| `wl-m1-collector` | `com.waterlogistics.collector` | 杨添爻 | 数据采集（含仿真器），全链源头 |
| `wl-m2-dispatch` | `com.waterlogistics.dispatch` | 卢肄文／张睿哲／吴可欣 | 调度引擎（M2 是唯一调度主控） |
| `wl-m3-rendezvous` | `com.waterlogistics.rendezvous` | 罗才辉为主 | 动态会面与路径规划 |
| `wl-m4-monitor` | `com.waterlogistics.monitor` | 彭紫情／宋冰鑫／吴可欣 | 飞行任务监控 |
| `wl-app` | `com.waterlogistics.app` | 吴可欣 | 组装层：启动类、配置、Bean 装配、`push/` 实时出口。**不含业务逻辑** |

## 一之二、`wl-contract` 冻结规则（10-15 首批落定后生效）

1. **只读**：除收口方（吴可欣）外，任何人不得直接提交 `wl-contract`。
2. **改约走 PR**：发现缺字段／字段不对，提 PR 或在群里报，由收口方裁决后统一改，禁止各自分叉。
3. **字面值已被测试钉死**：`ContractDoctrineTest` 把《22》§1.2 十二个主题、《23》§3–§7 四套状态词、
   《37》§5.12 告警类型与级别、《22》§3.1/§3.2 等待与闸门取值逐条对照，改错即刻编译失败。
4. **照抄不臆造**：字段一律取自《22-接口契约》v1.2 与《23-状态机定义》v0.6，
   文档没写的字段宁缺勿补；确有必要的由收口方回写文档后再补编码。

## 二、依赖方向（铁律，单向无环）

```
wl-app  ──→ m1 / m2 / m3 / m4
m1~m4   ──→ wl-algo、wl-storage、wl-contract
m2      ──→ m3        ← 唯一例外，只能调 m3 的 facade
wl-algo / wl-storage ──→ wl-contract ──→ wl-common
```

1. **模块间禁止 import 对方内部类**，运行期通信只走契约（MQ 主题 / REST / WS）。
2. **m2 → m3 是唯一允许的编译期跨模块调用**，且只调 `RendezvousFacade`；m3 不反向依赖 m2。
3. **船侧预测快照走端口反转**：m3 自己声明 `TrajectorySnapshotPort` 接口，m2 提供实现，`wl-app` 装配时接线——m3 全程不知道 m2 存在。
4. **`wl-algo` 不依赖 Spring Boot / MQ / storage**：阈值与台账参数由调用方注入，禁止硬编码。

依赖方向已在构建中固化：改错方向 `./gradlew build` 会直接失败。校验命令：

```bash
./gradlew :wl-algo:dependencies --configuration compileClasspath   # 应无任何 spring 出现
```

## 三、四条构建纪律

1. **只用 Wrapper**：`./gradlew build`，不要跑本机 `gradle`。
2. **只用 Kotlin DSL**：一律 `build.gradle.kts`，不混 Groovy `build.gradle`。
3. **版本只写一处**：第三方版本集中在 `gradle/libs.versions.toml`；**模块 build 脚本里不得出现版本号**。
4. **构建脚本改动等同公共设施变更**，须全绿后再提交。

JDK 版本全组自选（17 起），根脚本统一 `release 17` 保证编译产物一致；**具体 JDK 路径严禁写进仓库**，由各人的 `$GRADLE_USER_HOME/gradle.properties` 用 `org.gradle.java.home` 自备。

## 四、Spring Boot 4.x 三处差异（踩了直接编译失败）

| 旧 | 4.x 用 |
|---|---|
| `spring-boot-starter-web` | **`spring-boot-starter-webmvc`** |
| `com.fasterxml.jackson.*` | **`tools.jackson.*`**（`JsonMapper` 取代 `ObjectMapper`） |
| `mybatis-plus-spring-boot-starter` | **`mybatis-plus-spring-boot4-starter`** |

## 五、本地起服务

### 5.1 建库口径（改过 SQL 的先看这里）

建库**只在 MySQL 首次启动时发生一次**。`deploy/docker-compose.yml` 把 `./sql` 挂到
`/docker-entrypoint-initdb.d`，MySQL 只在**数据目录为空**时执行里面的 `001_init.sql`（建 14 表）
与 `002_sys_param.sql`（灌参数）；库一旦建成，重起容器不会再跑一遍。

所以：**改了 `deploy/sql/` 下任何 SQL（尤其是 `002_sys_param.sql` 加键），必须重建库，否则改动不生效。**

```bash
# 重建（会清空全部数据卷：mysql/redis/rabbitmq/mosquitto，不只是库）
cd deploy && docker compose down -v && docker compose up -d

# 等就绪：mysql / redis / rabbitmq 有 healthcheck，看 healthy；
#         mosquitto 没有 healthcheck，状态是 Up 即可
docker compose ps

# 核对灌入结果（行数应等于 002_sys_param.sql 的 INSERT 条数）
docker exec wavenest-mysql mysql -uroot -pwavenest -N -e "select count(*) from wavenest.sys_param;"
```

三个易错点：

| 易错做法         | 后果                                     |
| ---------------- | ---------------------------------------- |
| 只 `up -d`        | 库已存在，新键**不生效**，且没有任何报错提示       |
| 只 `down` 不带 `-v` | 具名卷 `mysql-data` 还在，重起仍不执行建库脚本         |
| 手工增量执行 SQL      | 与建库脚本口径不一致，别人重建后对不上（本组统一 `down -v`） |

> `down -v` 会连 redis／rabbitmq／mosquitto 的数据卷一起删；只清库数据不要动这几个时，
> 单独删 `mysql-data` 卷即可，但**加键场景请一律走 `down -v`**，口径统一才好排查。

### 5.2 起服务

```bash
# 1. 中间件（仓库根目录下）
cd deploy && docker compose up -d

# 2. 后端
./gradlew :wl-app:bootRun          # 监听 8080，默认 sim profile
./gradlew :wl-app:bootJar && java -jar backend/wl-app/build/libs/wl-app-0.1.0.jar
```

连接信息全部在 `backend/wl-app/src/main/resources/application.yml`，均可用环境变量覆盖
（`MYSQL_HOST`、`REDIS_PORT`、`RABBITMQ_HOST` 等），默认指向 compose 起的本机四件套。
**该文件是全组共享的，禁止写入个人端口与口令**：本机有差异时复制
`application-local.yml.example` 为 `application-local.yml`（已被 `.gitignore` 忽略，永不入库）
并在其中覆盖，主配置不会被污染。

RabbitMQ 管理台：<http://localhost:15672>（guest/guest）。