# Companion Call project descriptions / 唤伴发布文案

## About / 一句话简介

**English:** Call your companions and mounts to your side and choose them through a card interface.

**中文：**召回你的宠物与坐骑，通过卡片界面选择伙伴并查看状态。

## English description

Your companions do not have to be left behind when you travel. Companion Call lets you select a companion and call it to your side, whether it is nearby, in an unloaded area, or in another dimension.

### Call and choose

Tap **V** to call the selected companion. Hold **V** to open the card selector, scroll through your companions, and release the key to confirm the one in front. Press **Esc** to cancel. The key can be changed in Minecraft's control settings.

The HUD shows your selected companion and recall status. Cards show the companion's appearance and available details; for an unloaded companion, the displayed information comes from its last recorded state.

### Manage your companions

Tamed companions are registered as they are discovered. The management interface lets you hide entries and restore them later without harming the entity. You can also add the entity you are currently riding with the management interface or the default **N** key. This allows explicitly binding other entities such as boats and minecarts. Adding an entity that already has an owner requires administrator permission.

### How recall works

An eligible loaded companion navigates toward your changing position. Where recall requires it, the system locates and transfers the original entity, including across dimensions or from unloaded areas. Eligible mounts can carry you after they reach an appropriate boarding position. Recall does not revive dead companions.

### Installation and configuration

Install the standalone file for your Minecraft version and loader on both client and server. Fabric also requires Fabric API. KltytonUI and its required script runtime dependencies are included in the standalone file. The `embedded` variant is for a host that supplies KltytonUI; do not install it alongside the standalone version.

The summon and add keys can be changed in Minecraft's controls. Client configuration includes the HUD, selector, model previews, management entry, add key and HUD position. Common configuration includes the hold threshold and recall recovery timing. Commands are documented in the [README](../../README.md).

## 中文介绍

旅行时不必把伙伴留在身后。唤伴让你选择宠物或坐骑并召回到身边；伙伴在附近、未加载区域或其他维度时，都可以发起召回请求。

### 召回与选择

短按 **V** 召回当前伙伴。长按 **V** 打开卡片选择器，滚动切换伙伴，松开按键确认前方卡片；按 **Esc** 取消。召回键可在原版控制设置中修改。

HUD 显示当前伙伴和召回状态。卡片展示伙伴外观及可用信息；伙伴未加载时，显示的是上次记录的数据。

### 管理伙伴

已驯服的伙伴会在被发现时登记。管理界面可以隐藏条目并在之后恢复，不会伤害实体。你也可以通过管理界面或默认 **N** 键添加当前乘坐的实体，显式绑定船、矿车等其他实体。添加已有主人的实体需要管理员权限。

### 召回方式

符合条件且已加载的伙伴会向不断变化的玩家位置寻路。必要时，系统会定位并转移原实体，包括跨维度或从未加载区域召回。符合条件的坐骑到达合适的登乘位置后可以接上玩家。召回不会复活死亡的伙伴。

### 安装与配置

客户端和服务端都需要安装与 Minecraft 版本及加载器对应的独立版。Fabric 还需要 Fabric API。独立版包含 KltytonUI 和必要的脚本运行依赖。`embedded` 版供自行提供 KltytonUI 的宿主使用，不要与独立版同时安装。

召回键和添加键可在原版控制设置中修改。客户端配置包含 HUD、选择器、模型预览、管理入口、添加按键和 HUD 位置；公共配置包含长按阈值与召回恢复等待时间。指令说明见 [README](../../README.md)。
