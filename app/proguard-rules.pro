# Keep kotlinx.serialization generated serializers.
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
