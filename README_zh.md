# 呼朋唤友

![呼朋唤友图标](docs/publishing/artwork/companion-call-icon-400.png)

[English](README.md)

呼朋唤友让你召回自己的宠物和坐骑，通过卡片选择伙伴，并在 HUD 中查看当前状态。

## 功能

- **召回伙伴：**短按 **V** 召回当前选中的伙伴，按键可在原版控制设置中修改。
- **选择伙伴：**长按 **V** 打开卡片选择器，用滚轮切换，松开按键确认；按 **Esc** 取消。
- **查看状态：**HUD 显示当前伙伴和召回状态，可在选择界面或客户端配置中开关；宠物列表为空时自动隐藏。卡片展示伙伴外观及可用信息。
- **管理列表：**在选择器中打开管理界面，可隐藏或恢复条目。按 **N** 或使用管理界面可添加当前乘坐的实体；添加已有主人的实体需要管理员权限。
- **寻找伙伴：**已加载的伙伴会朝不断变化的玩家位置寻路；必要时，召回系统会定位并转移未加载或跨维度的原实体。

已驯服的伙伴在被发现时登记。船、矿车等其他实体也可通过乘坐入口显式绑定。列表和召回请求由服务端校验；隐藏条目不会杀死实体，模组也不会复活死亡的伙伴。

## 安装

客户端和服务端都需要安装与 Minecraft 版本、加载器对应的独立版。Fabric 还需要对应版本的 Fabric API。独立版包含 KltytonUI 及其脚本运行依赖。不要同时安装独立版与 `embedded` 版；后者供伊甸之境等自行提供 KltytonUI 的宿主使用。

| Minecraft | 加载器 | Java |
| --- | --- | --- |
| 1.20.1 | Forge、Fabric | 17 |
| 1.21.1 | NeoForge、Fabric | 21 |
| 26.1.2 | NeoForge、Fabric | 25 |
| 26.2 | NeoForge、Fabric | 25 |

客户端配置可控制 HUD、选择器、实体预览、管理入口、添加按键和 HUD 边距；公共配置可调整长按阈值及召回恢复等待时间。

## 指令

| 指令 | 用途 |
| --- | --- |
| `/companioncall list [page]` | 列出自己的伙伴。 |
| `/companioncall select <uuid>` | 选择自己的伙伴。 |
| `/companioncall recall [uuid]` | 召回当前或指定的伙伴。 |
| `/companioncall force-recall <player> <uuid>` | 管理员定位并转移玩家的伙伴。 |
| `/companioncall set-owner <entity> <player>` | 管理员设置或转让所有权。 |

## 从源码构建

各目标使用自己的 Gradle Wrapper。将匹配的 KltytonUI JAR 放入 `libs/kltytonui/<加载器>-<KUI 目标>`，然后在目标目录执行 `./gradlew build`；Windows 使用 `gradlew.bat build`。Minecraft 26.1.2 使用 KUI 的 `26.1` 目标名。1.20.1 构建进程使用 Java 21，编译工具链使用 Java 17。产物位于对应目标的 `build/libs`。

## 致谢与许可

呼朋唤友源码采用 [MIT 许可证](LICENSE)。KltytonUI 采用 LGPL-2.1 许可证，第三方声明随发行包提供。哨声资源来自 [Eugene's Horse Whistle](https://github.com/kltyton/eugenes_horse_whistle/blob/b9910328c59a0924ce4f44c4a03b1fe12d46b453/src/main/resources/assets/eugenes_horse_whistle/sounds/whistle.ogg)。
