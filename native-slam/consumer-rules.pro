# JNI entry points are resolved by name from C++ (JNIEXPORT ... NativeSlamEngine_nativeXxx),
# so R8 must not rename or strip them.
-keepclasseswithmembers,includedescriptorclasses class com.constructar.nativebridge.NativeSlamEngine {
    native <methods>;
}
-keep class com.constructar.nativebridge.NativeSlamEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
