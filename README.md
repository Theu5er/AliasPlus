# Alias+
基于Nukkit-MOT核心打造的Minecraft基岩版小号检测插件

#项目优势：
与市面上其他反小号插件不同，我们不仅支持DID,SSID,CID等基础检查，还开发了基于资源包缓存与BlobCache的冷门检测，专门为检测Spoofer设计

#使用：
在文件server.properties中启用force-resources
/alias <名称/uuid/xuid>
输出示例：
player's accounts (uuid: b1033201-7292-3cac-9bf0-2059ac139adc)--
DeviceId: player1
SelfSignedId: player1
ClientRandomId: player1
ResPackCache: player1
BlobCache: player1

#注意事项：
本项目初衷为技术演示，未经全面严格测试，或许存在bug，请勿在成品服务器上使用
ResPackCache输出有被伪造可能，但在需要时，可以直接封禁返回的所有账号，因为他们只可能是小号或作弊者
