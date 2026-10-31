package com.constructar.arengine.render

import android.util.Log
import com.google.android.filament.MaterialInstance
import java.util.concurrent.ConcurrentHashMap

/**
 * Version-adaptive material creation for SceneView.
 *
 * SceneView's `MaterialLoader` is the only supported way to obtain a `.filamat`-backed
 * [MaterialInstance] for custom geometry, but its factory-method names varied across the 2.x,
 * 3.x and 4.x lines (`createColorInstance`, `createUnlitColorInstance`, and overloads taking
 * an `io.github.sceneview.math.Color` or a packed ARGB `Int`).
 *
 * Rather than pinning the whole renderer to one of those signatures — which would break the
 * build on a patch bump — the lookup is done reflectively **once per colour** and cached. The
 * moment a material cannot be created, the renderer skips that mesh and the app keeps working
 * (measuring never depends on rendering). Pin a SceneView version and replace this file with
 * direct calls if you prefer the compile-time check.
 */
class SceneViewMaterialProvider(private val materialLoader: Any) : ColorMaterialProvider {

    private val cache = ConcurrentHashMap<Int, MaterialInstance?>(16)

    override fun create(argb: Int): MaterialInstance? = cache.getOrPut(argb) {
        createUncached(argb)
    }

    private fun createUncached(argb: Int): MaterialInstance? {
        // Order matters: a lit material reads correctly under ARCore's light estimation,
        // which is what makes a pipe look like it is lying on a wall rather than pasted on.
        val attempts = listOf(
            "createColorInstance" to listOf(intArrayOf(argb)),
            "createColorInstance" to listOf(arrayOf(argbColor(argb))),
            "createUnlitColorInstance" to listOf(intArrayOf(argb)),
            "createUnlitColorInstance" to listOf(arrayOf(argbColor(argb))),
        )
        for ((name, args) in attempts) {
            val instance = invoke(name, args) ?: continue
            return instance
        }
        Log.w(TAG, "MaterialLoader exposes no colour factory — custom meshes will be invisible")
        return null
    }

    private fun invoke(name: String, args: List<Any>): MaterialInstance? {
        val method = materialLoader.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == args.size &&
                candidate.parameterTypes.indices.all { index ->
                    val type = candidate.parameterTypes[index]
                    when (val argument = args[index]) {
                        is Int -> type == Int::class.javaPrimitiveType || type == Int::class.java
                        is Array<*> -> type.isAssignableFrom(argument[0]?.javaClass ?: Any::class.java)
                        else -> type.isAssignableFrom(argument.javaClass)
                    }
                }
        } ?: return null
        return runCatching { method.invoke(materialLoader, *args.toTypedArray()) as? MaterialInstance }
            .onFailure { Log.w(TAG, "material factory $name failed: ${it.message}") }
            .getOrNull()
    }

    private fun argbColor(argb: Int): Any {
        // SceneView's Color is a data class over (r, g, b, a) floats. Built reflectively for
        // the same version-tolerance reason as above.
        val colorClass = runCatching { Class.forName("io.github.sceneview.math.Color") }.getOrNull()
            ?: return argb
        val floats = floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            ((argb ushr 24) and 0xFF) / 255f,
        )
        val ctor = colorClass.constructors.firstOrNull { it.parameterCount == 4 }
            ?: return argb
        return runCatching { ctor.newInstance(floats[0], floats[1], floats[2], floats[3]) }
            .getOrElse { argb }
    }

    companion object {
        private const val TAG = "SceneViewMaterials"
    }
}

/** A material provider that never returns anything (headless/unit-test builds). */
object NoOpMaterialProvider : ColorMaterialProvider {
    override fun create(argb: Int): MaterialInstance? = null
}
