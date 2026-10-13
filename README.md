# FreeMarket 自由市场

适用于 **Paper 26.3、Java 25** 的玩家交易插件。玩家将实物存为余额，远程购买商品，等待后到该市场任意区块取货。每名玩家全服只能拥有一个市场，市场可包含主世界、下界、末地的分散区块。

支持整数实物存入、两位小数余额、自由挂单、远程部分购买、延迟跨维度取货和市场税收。完整功能规则详见 [需求确认.md](需求确认.md)。

从 [GitHub Releases](https://github.com/LFK0560/FreeMarket/releases/latest) 下载已编译的插件。源码使用 [MIT 许可证](LICENSE)，第三方依赖声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 安装

1. 使用 Java 25 启动 Paper 26.3。停止服务器。
2. 将 `FreeMarket-1.0.0.jar` 放入服务器的 `plugins/`，再启动服务器。插件自带 SQLite 驱动，无须 Vault、经济插件或数据库服务。
3. 首次启动生成 `plugins/FreeMarket/config.yml`、`categories.yml`、`lang/zh_CN.yml` 和 `market.db`。
4. 在服务器终端执行 `marketadmin setadmin 玩家名`。玩家登录后可执行 `/market admin`。
5. 修改配置后执行 `marketadmin reload`。替换插件 JAR 或改变数据库文件路径后需要重启。

Java 25 可在启动参数中加入 `--enable-native-access=ALL-UNNAMED`，允许内置 SQLite 驱动调用本机库。测试服使用该参数完成验证。

插件使用玩家 UUID 保存账户和所有权；离线模式使用服务器产生的离线 UUID。切换正版验证模式或更改玩家名可能产生不同身份，迁移前应备份数据。

## 玩家操作

- `/market` 打开主界面。顶部分类、收藏；底部排序、搜索、我的挂单、待取货、余额、市场管理和上架。搜索按钮左键输入、右键刷新；“全部商品”右键发送最近市场坐标。
- 余额界面支持存入指定数量、存入全部、提现指定整数数量和提现全部整数余额。这些操作允许在任何位置进行。
- 站在目标区块，打开“我的市场”，支付创建费用。之后站在其他未被占用的区块扩建，包括其他维度。一个区块覆盖该世界的全部高度。
- 在任何人的市场内执行 `/market sell`。点击背包物品，输入数量和每件价格；可选择多件商品、统一改价、调整时长。确认之前物品仍在背包，关闭界面不会扣物品。
- 任何位置都可以浏览、搜索、收藏和购买。详情页提供购买 1 件、16 件、全部或自定义数量，需要确认。买家余额扣除、卖家收入和市场税收在同一事务中完成。
- `/market pending` 查看已购买、下架或过期的商品。购买后默认等待 10 秒，之后到该挂单所属市场的任意区块领取，可跨维度。界面会显示符合条件的最近区块坐标；优先当前世界，不自动传送。
- `/market my` 查看自己的挂单。右键改价，Shift + 右键下架，这两项操作也必须在市场内完成。已售部分不会被撤销，剩余商品进入待取货。
- 市场所有者可调整一个总税率、领取税收、管理区块和关闭市场。税收全额转入余额，提现只兑换整数部分。

市场区域只用于交易规则，**不提供方块、实体或领地保护**。所有玩家都可在别人建立的市场上架。

## 余额、税费与关闭规则

每件当前配置的货币物品对应 `1.00` 余额。余额、价格和税费以整数分保存，拒绝超过两位小数的输入。默认货币为普通钻石，带自定义属性的钻石默认不能存入。

例如购买 4 件、每件 `0.25` 的商品，总额 `1.00`，税率 5%：买家扣 `1.00`，卖家立即收到 `0.95`，市场所有者收到 `0.05` 税收。税费使用四舍五入，每次成交单独计算。卖家不需要在线。

商品上架时锁定市场税率。所有者之后改税率只影响新挂单；范围 `0.00%`～`100.00%`。部分购买后，余量继续出售，售完才标记为已售出。价格历史按完整物品属性分别统计，忽略堆叠数量，不混合不同附魔、耐久、名称或自定义数据的商品。

修改 `currency.material` 不换算已有余额。比如改为 `EMERALD` 后，已有 `12.34` 仍为 `12.34`，提现 12 件发放绿宝石并留下 `0.34`。默认不能上架当前货币；更换后旧货币不再受这一规则限制。

每个区块默认收费 `64.00`，费用直接消耗、不退还。调整费用只影响之后创建或扩建。市场管理员同样支付费用。移除最后一个区块等同关闭市场；关闭要求没有活跃挂单、可领取或领取中商品、相关未处理实物操作。完成的历史成交不影响关闭，余下税收自动转入创建者余额。

## 指令与权限

`/market` 的别名为 `/fm`、`/mkt`、`/shop`、`/市场`。命令通过 Paper 生命周期注册，因此不使用旧版 plugin.yml 指令注册。

| 指令 | 用途 | 使用者 |
|---|---|---|
| `/market` | 浏览全服商品 | 玩家 |
| `/market sell` | 选择背包商品上架 | 有上架权限、位于市场的玩家 |
| `/market my` | 我的挂单 | 玩家 |
| `/market pending` | 待取货 | 玩家 |
| `/market admin`、`/marketadmin` | 管理面板 | 终端添加的市场管理员 |
| `/market reload`、`/marketadmin reload` | 重载配置、分类和语言 | 市场管理员或服务器终端 |
| `marketadmin setadmin 玩家名` | 添加市场管理员 | **仅服务器终端或 RCON** |
| `marketadmin removeadmin 玩家名` | 移除市场管理员 | **仅服务器终端或 RCON** |
| `/marketadmin listadmin` | 查看管理员名单 | 市场管理员或服务器终端 |
| `/marketadmin stats` | 查看最近 24 小时统计 | 市场管理员或服务器终端 |

| 权限节点 | 默认 | 作用 |
|---|---|---|
| `freemarket.use` | 所有玩家 | 打开自由市场 |
| `freemarket.sell` | 所有玩家 | 上架、改价、下架 |
| `freemarket.buy` | 所有玩家 | 购买 |
| `freemarket.favorite` | 所有玩家 | 收藏 |
| `freemarket.limit.vip` | 关闭 | 活跃挂单上限默认 20 |
| `freemarket.limit.mvp` | 关闭 | 活跃挂单上限默认 40 |
| `freemarket.limit.<组名>` | 需权限插件授予 | 对应配置中的上限，取符合条件的最大值 |
| `freemarket.bypass.limit` | 关闭 | 忽略活跃挂单数量上限 |
| `freemarket.bypass.blacklist` | 关闭 | 忽略可配置上架黑名单 |

OP 或通配权限不会自动成为市场管理员，也不能在游戏内添加管理员。管理员名单 GUI 只读。无需安装权限插件，普通玩家默认可使用；可选用权限插件设置组上限。

## 管理员上手五步

1. 终端执行 `marketadmin setadmin 玩家名`，让该玩家登录。
2. 在配置文件确定货币材质，重载；普通账户存入货币用于测试。
3. 站在要建立市场的区块，在“我的市场”支付创建费用，再按需扩建。
4. 打开 `/market admin`，检查全局规则、挂单上限、黑名单和统计。市场税率由各所有者在自己的市场管理页设置。
5. 遇到冻结操作时打开“冻结操作”，核对操作类型、玩家和操作前后背包快照，然后选择相应处理方式。

## 配置说明

配置中的材质使用 Bukkit `Material` 名称，例如 `DIAMOND`、`EMERALD`。所有金额输入最多两位小数。规则 GUI 修改后会保存到配置文件；货币只允许编辑配置文件。

| 配置路径 | 默认值 | 含义 |
|---|---|---|
| `currency.material` | DIAMOND | 存入、提现使用的当前材质 |
| `currency.allow-custom-nbt` | false | 是否接受带属性的货币物品；提现始终发普通物品 |
| `market.chunk-cost` | 64.00 | 每区块创建费用 |
| `market.default-tax-percent` | 5.00 | 新建市场默认税率 |
| `market.max-chunks-per-owner` | 0 | 每人区块上限，0 为不限 |
| `market.allowed-environments` | NORMAL, NETHER, THE_END | 允许创建、扩建的维度类型 |
| `listing.default-duration-hours` | 72 | 默认上架时长 |
| `listing.max-duration-hours` | 168 | 上架最长时长 |
| `listing.category-duration-hours` | food:24, enchanted_book:48 | 分类默认时长，与税率无关 |
| `listing.max-active-listings-default` | 10 | 默认活跃挂单上限 |
| `listing.max-active-listings-by-group` | vip:20, mvp:40 | 权限组挂单上限 |
| `listing.max-stack-amount` | 64 | 一笔挂单最多数量 |
| `listing.cooldown-seconds` | 3 | 上架冷却 |
| `listing.block-currency` | true | 禁止上架当前货币 |
| `price.min-unit-price` | 0.01 | 最低单价 |
| `price.max-unit-price` | 0 | 最高单价，0 无额外上限 |
| `price.deviation-warn-percent` | 300 | 新价格偏离均价时提示 |
| `delivery.delay-seconds` | 10 | 购买后等待秒数 |
| `delivery.expire-scan-seconds` | 60 | 过期扫描间隔；成交还会检查实际到期时间 |
| `blacklist.enabled` | true | 启用可配置黑名单 |
| `blacklist.materials` | 见配置 | 禁售材质，递归检查收纳袋和潜影盒内容 |
| `blacklist.block-all-custom-nbt` | false | 拒绝有物品属性的商品 |
| `blacklist.block-unstackable` | false | 拒绝不可堆叠商品 |
| `chart.points` | 24 | 走势字符点数 |
| `chart.granularity` | DAY | HOUR 或 DAY 聚合 |
| `chart.window-hours` | 720 | 统计最近多少小时 |
| `chart.min-trades` | 2 | 展示走势所需成交笔数 |
| `chart.cache-seconds` | 60 | 价格统计缓存时间 |
| `chart.blocks` | ▁▂▃▄▅▆▇█ | 走势字符序列 |
| `storage.file` | market.db | 插件数据目录内的 SQLite 文件 |
| `storage.keep-trade-days` | 90 | 成交历史保留天数 |
| `general.page-size` | 36 | 每页挂单数量，最大 36 |
| `general.broadcast-new-listing` | false | 上架广播 |
| `general.broadcast-trade` | false | 成交广播 |
| `general.sound-enabled` | true | GUI 提示音 |

`categories.yml` 定义类别材质与后缀匹配；`lang/zh_CN.yml` 定义中文界面、提示、物品搜索别名和 MiniMessage 样式。配置值越界或货币材质无效时重载会报错并保留当前有效设置。

## 实物操作与异常恢复

余额购买使用单个 SQLite 事务，统一修改买家余额、挂单余量、卖家收入、市场税收、待取货记录和成交历史。数据库使用串行工作线程、WAL 和 FULL 同步；背包及 Paper 物品序列化只在主线程执行。

存入、提现、上架和取货会先保存操作及完整背包前后快照，再校验实际背包、修改实物、保存玩家数据，最后确认记账。处理中阻止该玩家移动区块、丢弃、拾取、使用物品或再操作库存。无法确认实物是否执行的记录进入冻结状态，重启也会把未确认操作冻结。

游戏背包和 SQLite 无法组成同一个数据库事务，因此强制杀进程后的不确定状态需要人工核查。管理员界面提供两种决策：

- **实物已执行**：确认现有记账，释放预留，完成该操作；不会再次发放或扣除实物。
- **实物未执行**：撤销余额或商品预留，恢复相关记录；不会自动修改玩家背包。

应先对照操作前后快照、玩家保存数据和实际背包，确认对应事实再选择。每次处理记录管理员 UUID、决策和时间；重复处理被拒绝。被冻结的玩家不能再发起资金或实物交易操作，已完成的正常交易不回滚。

备份建议正常 `stop` 后复制整个 `plugins/FreeMarket/`，同时保存世界中的 `playerdata/`。如果在线复制 SQLite，要连同 `market.db-wal` 和 `market.db-shm` 保持一致。

## 编译与工程结构

需要 JDK 25、Maven 3.9+，首次构建需要下载依赖：

```sh
mvn clean package
```

或运行 `./build.sh`。生成文件为 `target/FreeMarket-1.0.0.jar`。测试覆盖真实 SQLite 事务、并发抢购、税费舍入、延迟与重复领取、过期、关闭条件、崩溃恢复及走势图压缩。验收证据和未执行项见 [验收清单.md](验收清单.md)。

工程将相关实现集中在完整业务类中：`storage` 保存事务与恢复，`market` 编排主线程和异步流程，`gui` 提供全部菜单与铁砧输入，`region` 缓存跨维度区块，`chart` 生成走势，`config` 加载配置和语言，`command` 和 `listener` 注册指令并保护库存。源码不包含占位实现。

GitHub Actions 会在推送和 Pull Request 时使用 Java 25 编译、执行测试并保存构建产物。欢迎通过 Issue 提交复现步骤，或通过 Pull Request 改进功能；交易与恢复逻辑的修改应附带相应测试。

## Paper 26.3 适配

依赖固定为 `io.papermc.paper:paper-api:26.3.build.178-stable`，插件声明 `api-version: '26.3'`，编译使用 Java 25 和 `-Xlint:deprecation -Werror`。使用 Adventure Component、MiniMessage、Paper BasicCommand、类型化 `MenuType.ANVIL` 和 Paper 二进制物品序列化；`ItemStack#getType()` 实际返回 `Material`。

SQLite 驱动 `3.53.4.0` 打包进插件，保留 `org.sqlite` 包名。原提示词要求普通 shade 重定位，但 JNI 本机库依赖原类名，直接重定位会破坏调用；插件类加载隔离配合完整驱动打包完成实机验证。Paper API 和服务器日志实现不打进插件。

官方资料：[Paper 工程配置](https://docs.papermc.io/paper/dev/project-setup/)、[插件声明](https://docs.papermc.io/paper/dev/plugin-yml/)、[Paper 26.3 API](https://jd.papermc.io/paper/26.3/)、[Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc)。
