# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ==========================================
# 【修复】高德地图SDK ProGuard 混淆规则
# ==========================================

# 3D地图 V5.0.0之后
-keep class com.amap.api.maps.**{*;}
-keep class com.autonavi.**{*;}
-keep class com.amap.api.trace.**{*;}

# 定位
-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.autonavi.aps.amapapi.model.**{*;}

# 搜索
-keep class com.amap.api.services.**{*;}

# 导航 V8.1.0及以后
-keep class com.amap.api.navi.**{*;}
-keep class com.alibaba.idst.nui.* {*;}
-keep class com.google.**{*;}

# 导航涉及的语音合成相关包
-keep class com.alibaba.mit.alitts.*{*;}
-keep class com.alibaba.idst.nls.** {*;}

# 防止导航回调接口被混淆
-keep interface com.amap.api.navi.AMapNaviListener { *; }
-keep class * implements com.amap.api.navi.AMapNaviListener { *; }

# 导航模型类
-keep class com.amap.api.navi.model.** { *; }

# 防止反射调用的问题
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# ==========================================
# 其他第三方库混淆规则
# ==========================================

# Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# TensorFlow Lite
-keep class org.tensorflow.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
