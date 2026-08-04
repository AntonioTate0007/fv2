# Pumps app — Compose + Retrofit + kotlinx.serialization. Default Android
# rules cover Compose; the rules below keep retrofit/serialization happy.

-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep models serialized over the wire.
-keep class com.pumpfinder.app.data.** { *; }
