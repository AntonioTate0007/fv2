# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fortress.app.**$$serializer { *; }
-keepclassmembers class com.fortress.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
