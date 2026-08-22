# Regole R8 — le librerie principali (Room, Retrofit, OkHttp, kotlinx.serialization,
# Compose) incorporano già le proprie consumer rules; qui solo il minimo sindacale.

# kotlinx.serialization: serializzatori generati per i data class del backup
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class com.adgent.trader.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.adgent.trader.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit: interfacce API con generics (mantieni firme per reflection)
-keepattributes Exceptions
-keep,allowobfuscation interface com.adgent.trader.core.network.BinanceApi
-keep,allowobfuscation interface com.adgent.trader.core.network.** { *; }
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
