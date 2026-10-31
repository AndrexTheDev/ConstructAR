# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
#
# Consumer rules for :native-slam.
#
# There is no reflection anywhere in ConstructAR's own code (enforced in CI by
# scripts/verify_sources.py), so no class needs to be kept by name for the library itself to
# work. What does need protecting is the JNI surface: native code looks methods up by name and
# signature, so R8 must not rename or strip them.

# Every method of NativeSlamEngine is an `external fun` resolved by name from slam_jni.cpp.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.constructar.nativebridge.NativeSlamEngine {
    native <methods>;
}
