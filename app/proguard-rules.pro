# Selkies 客户端混淆规则（当前未开启 minify，预留）
-keep class com.selkies.client.net.Protocol* { *; }
-keep class com.selkies.client.data.ServerConfig { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
