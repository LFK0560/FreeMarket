# 第三方依赖声明

FreeMarket 自身的源码与文档使用根目录 [LICENSE](LICENSE) 中的 MIT 许可证。第三方组件保留各自的许可与版权声明。

插件 JAR 包含 Xerial `sqlite-jdbc 3.53.4.0`，项目地址为 <https://github.com/xerial/sqlite-jdbc>。驱动保留 Apache License 2.0 和源自 David Crawshaw 的 BSD 许可；原始文本分别保存在 JAR 的 `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` 与 `LICENSE.zentus`，源码仓库的 `licenses/` 也提供副本。

Paper API、SLF4J API 为 provided 依赖，不随插件打包；SLF4J NOP、JUnit 和构建工具只用于测试或构建。Minecraft 服务端和本地测试世界不在本仓库及发布资产中。
