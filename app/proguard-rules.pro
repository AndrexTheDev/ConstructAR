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

# ── Licence and tamper checks ──────────────────────────────────────────────────────────────
# Deliberately *no* `-keep` for the licence classes: they are reachable from `ProjectViewModel`
# and every export gate is reachable from the UI, so R8 cannot remove them - and leaving them
# obfuscatable keeps the names out of a would-be patcher's hands. What must be verified is that
# the checks survive optimisation at all, which the workflow does by grepping the release DEX
# for the algorithm and wire-format markers (see .github/workflows/android-build.yml). A silent
# loss of the ECDSA path would otherwise accept every forged key.
-dontwarn javax.crypto.**
-dontwarn java.security.**
