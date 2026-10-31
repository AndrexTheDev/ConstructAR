# Room, Retrofit and Firebase are absent by design: this app has no network stack at all.
# The rules below only cover what ARCore/Filament/SceneView and kotlinx.serialization need.

# ARCore uses reflection for its native bindings.
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# Filament's JNI layer is looked up by class name.
-keep class com.google.android.filament.** { *; }

# SceneView's MaterialLoader/ModelLoader are resolved reflectively by
# com.constructar.arengine.render.SceneViewMaterialProvider.
-keep class io.github.sceneview.** { *; }
-dontwarn io.github.sceneview.**

# kotlinx.serialization: keep the generated serializers of the persisted data model.
-keepattributes *Attributes*, InnerClasses
-keep,includedescriptorclasses class com.constructar.core.model.**$$serializer { *; }
-keepclassmembers class com.constructar.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.constructar.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# TFLite / LiteRT delegates are instantiated by name (NNAPI, GPU).
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
