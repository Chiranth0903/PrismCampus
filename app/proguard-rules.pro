# PrismCampus Proguard rules
-keep class com.prismcampus.app.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
