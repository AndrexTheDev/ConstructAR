# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
#
# Consumer rules for :ar-engine.
#
# There is no reflection anywhere in ConstructAR's own code (enforced in CI by
# scripts/verify_sources.py), so no class needs to be kept by name for the library itself to
# work. What does need protecting is the JNI surface: native code looks methods up by name and
# signature, so R8 must not rename or strip them.

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Filament calls back into a handful of Java helpers by name (EntityManager, RenderableManager
# builders). Keeping their names costs a few hundred bytes and removes a whole class of
# "works in debug, crashes in release" reports.
-keepnames class com.google.android.filament.** { *; }
