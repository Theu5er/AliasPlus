# Alias+

A Minecraft Bedrock Edition alt-account detection plugin built on the Nukkit-MOT core.  

**Advantages:**

Unlike other anti-alt plugins on the market, we not only support basic checks like DID, SSID, and CID, but also have developed detection based on resource pack cache and BlobCache—less common methods designed specifically to catch spoofers.

**Usage:**

Enable force-resources in the server.properties file.
/alias <name/uuid/xuid>

Example output:

```
player's accounts (uuid: b1033201-7292-3cac-9bf0-2059ac139adc)--
DeviceId: player1
SelfSignedId: player1
ClientRandomId: player1
ResPackCache: player1
BlobCache: player1
```

**Notes:**

This project was originally intended as a technical demonstration. It has not undergone comprehensive or rigorous testing and may contain bugs—please do not use it on a production server.

The ResPackCache output can potentially be spoofed, but when necessary, you can simply ban all accounts returned, since they can only be alt accounts or cheaters.
