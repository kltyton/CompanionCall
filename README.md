# Companion Call / 唤伴

Call your companions and mounts to your side, choose between them in a card interface, and keep their status close at hand.

[中文说明](#中文说明) · [Project descriptions / 发布文案](docs/publishing/descriptions.md)

## For players

- **Call a companion:** tap **V** to call the selected companion. The key can be changed in Minecraft's controls.
- **Choose a companion:** hold **V** to open the card selector, scroll to choose, and release the key to confirm. Press **Esc** to cancel.
- **Keep track:** the HUD displays the selected companion and recall status. Cards show the companion's appearance and available details.
- **Manage the list:** open management from the selector to hide or restore entries. Press **N**, or use the management interface, to add the entity you are riding. Adding an entity with an owner requires administrator permission.
- **Find your way back together:** a loaded companion navigates toward your changing position. When necessary, the recall system can locate and transfer the original entity across dimensions or from unloaded areas.

Tamed companions are registered as they are discovered. Other entities, including boats and minecarts, can be explicitly bound through the riding entry. The list and recall requests are checked by the server. A hidden entry does not kill the entity, and the mod does not resurrect dead companions.

### Installation

Install the standalone file matching your Minecraft version and loader on both client and server. Fabric also requires the matching Fabric API. The standalone file contains KltytonUI and its required script runtime dependencies. Do not install the standalone and `embedded` variants together; `embedded` is intended for a host such as Eden Realm that supplies KltytonUI itself.

| Minecraft | Loader | Java |
| --- | --- | --- |
| 1.20.1 | Forge, Fabric | 17 |
| 1.21.1 | NeoForge, Fabric | 21 |
| 26.1.2 | NeoForge, Fabric | 25 |
| 26.2 | NeoForge, Fabric | 25 |

Client settings control the HUD, selector, previews, management entry, add key, and HUD margins. Common settings control the hold threshold and recall recovery timing. Defaults are in Minecraft's controls and the `companion_call` configuration files.

### Commands

| Command | Purpose |
| --- | --- |
| `/companioncall list [page]` | List your registered companions. |
| `/companioncall select <uuid>` | Select one of your companions. |
| `/companioncall recall [uuid]` | Call the selected or specified companion. |
| `/companioncall force-recall <player> <uuid>` | Administrator: locate and transfer a player's companion. |
| `/companioncall set-owner <entity> <player>` | Administrator: set or transfer ownership. |

## 中文说明

唤伴让你召回自己的宠物和坐骑，通过卡片选择伙伴，并在 HUD 中查看当前状态。

- **召回伙伴：**短按 **V** 召回当前选中的伙伴，按键可在原版控制设置中修改。
- **选择伙伴：**长按 **V** 打开卡片选择器，用滚轮切换，松开按键确认；按 **Esc** 取消。
- **查看状态：**HUD 显示当前伙伴和召回状态，卡片展示伙伴外观及可用信息。
- **管理列表：**在选择器中打开管理界面，可隐藏或恢复条目。按 **N** 或使用管理界面可添加当前乘坐的实体；添加已有主人的实体需要管理员权限。
- **寻找伙伴：**已加载的伙伴会朝不断变化的玩家位置寻路；必要时，召回系统会定位并转移未加载或跨维度的原实体。

已驯服的伙伴在被发现时登记。船、矿车等其他实体也可通过乘坐入口显式绑定。列表和召回请求由服务端校验；隐藏条目不会杀死实体，模组也不会复活死亡的伙伴。

### 安装

客户端和服务端都需要安装与 Minecraft 版本、加载器对应的独立版。Fabric 还需要对应版本的 Fabric API。独立版包含 KltytonUI 及其脚本运行依赖。不要同时安装独立版与 `embedded` 版；后者供伊甸之境等自行提供 KltytonUI 的宿主使用。

支持的版本和 Java 要求见上方表格。客户端配置可控制 HUD、选择器、实体预览、管理入口、添加按键和 HUD 边距；公共配置可调整长按阈值及召回恢复等待时间。指令及管理员权限见上方表格。

## Building from source / 从源码构建

Each target has its own Gradle Wrapper. Put the matching KltytonUI JAR in `libs/kltytonui/<loader>-<KUI target>` and run `./gradlew build` (or `gradlew.bat build` on Windows) from the target directory. Minecraft 26.1.2 uses the KUI `26.1` target name. The Gradle process for 1.20.1 uses Java 21 with a Java 17 compilation toolchain. Build output is placed in the target's `build/libs` directory.

各目标使用自己的 Gradle Wrapper。将对应的 KltytonUI JAR 放入上述目录后，在目标目录执行构建；1.20.1 构建进程使用 Java 21，编译工具链使用 Java 17。

## Credits and license / 致谢与许可

Companion Call is licensed under [MIT](LICENSE). KltytonUI is licensed under LGPL-2.1; third-party notices are included with the distribution. The whistle sound is derived from [Eugene's Horse Whistle](https://github.com/kltyton/eugenes_horse_whistle/blob/b9910328c59a0924ce4f44c4a03b1fe12d46b453/src/main/resources/assets/eugenes_horse_whistle/sounds/whistle.ogg).
