# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
#
# Consumer rules for :export.
#
# There is no reflection anywhere in ConstructAR's own code (enforced in CI by
# scripts/verify_sources.py), so no class needs to be kept by name for the library itself to
# work. What does need protecting is the JNI surface: native code looks methods up by name and
# signature, so R8 must not rename or strip them.

# :export uses kotlinx.serialization only for the project JSON it reads back; the generated
# serializers are kept by their companion objects, which R8 already treats as reachable.
-keepclasseswithmembernames class kotlinx.serialization.** { *; }
