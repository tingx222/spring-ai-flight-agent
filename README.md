# Spring AI Flight Agent · 机票售后助手

基于 Spring AI Alibaba 官方 `playground-flight-booking` 示例二次开发的 Java Agent 学习项目。通过中文对话查询已有订单、生成改签或取消预览，用户确认后由 Java 后端执行，并保存 MySQL 订单和审计。

## 来源与许可

- [官方示例仓库](https://github.com/spring-ai-alibaba/examples)
- [原始模块](https://github.com/spring-ai-alibaba/examples/tree/main/spring-ai-alibaba-agent-example/playground-flight-booking)
- 2026-10-17 从 main 分支源码压缩包提取模块及两层父POM，本仓库从本地改进版本建立新历史，不包含官方Git历史或可核实的原始提交SHA。
- 保留 [Apache-2.0 LICENSE](LICENSE) 和原源码许可声明。

## 技术栈

Java 17、Spring Boot 3.5.7、Spring AI 1.1.0、Spring AI Alibaba DashScope 1.1.2.1、WebFlux/SSE、Vue 3/TypeScript/Vite、JdbcTemplate、MySQL、HikariCP、JUnit 5/Mockito/H2。聊天模型默认qwen-max，可配置。

## 已完成功能

- 订单查询、条款RAG与角色消息记忆。
- 内存/MySQL两种订单存储，MySQL模式重启后保留订单及审计。
- 日期解析、状态校验、原航线保护及有可信审计依据的空航线恢复。
- 从用户消息提取编号，以应用侧ToolContext约束工具调用。
- 改签与取消先生成后端预览，下一轮确认才执行；过期、订单变化、撤销及重复确认保护。
- 订单更新与审计写入处于同一事务；审计失败回滚订单。
- 现有测试报告累计54项通过，含不保留Java参数名时的审计HTTP接口回归。

## 本机运行

需要JDK17、Maven3.9+、有效百炼密钥。MySQL模式另需MySQL及flight_booking数据库。已包含可直接运行的前端资源，首次体验不必安装Node依赖。

1. 在MySQL客户端执行 `mysql-init.sql`。
2. Windows PowerShell运行 `./build.ps1` 打包。
3. 双击 `start-mysql.cmd`，依次隐藏输入数据库密码和完整百炼API Key。
4. 打开 [http://localhost:9000](http://localhost:9000)。

默认数据库为localhost:3306、root、flight_booking，可通过DB_HOST、DB_PORT、DB_USERNAME、DB_NAME、DB_PASSWORD覆盖。root仅为本机开发默认，部署应使用专用最小权限账号。

普通 `start.cmd` 使用内存订单。密钥与密码不应写入源码或提交；当前脚本仅在本次启动进程中使用隐藏输入值。IDEA运行及环境变量配置详见下方指南。

## 测试

在项目根目录执行：

```powershell
mvn -s ./maven-settings.xml "-Dmaven.repo.local=$PWD/.m2/repository" -f ./spring-ai-alibaba-agent-example/playground-flight-booking/pom.xml test
```

测试使用H2/内存和模拟模型，不要求真实密钥或连接用户MySQL；这不等同于真实模型准确率或生产性能验证。`build.ps1` 默认跳过测试。

## 文档

- [运行说明](README-运行说明.md)
- [项目讲解与源码阅读指南](项目讲解与源码阅读指南.md)：IDEA、技术栈、架构图、调用流程、Agent知识点与源码对应。
- [验证记录](验证记录.md)
- [后续开发与提交约定](CONTRIBUTING.md)

## 当前边界与后续方向

这是已有机票订单售后模拟项目，未接入真实航空公司、库存、新购票、支付或退款。尚无登录鉴权，订单与审计接口仅用于本机演示，不应直接公开部署。

聊天、向量索引及确认方案位于单实例内存；确认保护不等于跨实例/跨重启持久幂等。数据库初始化尚未使用版本迁移工具。后续计划按正常Java业务工程完善分布式认证、订单归属、用户界面、迁移管理和端到端流程。
