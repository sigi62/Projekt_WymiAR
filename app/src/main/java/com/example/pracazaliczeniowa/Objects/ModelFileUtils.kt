package com.example.pracazaliczeniowa.Objects

import android.content.Context
import com.example.pracazaliczeniowa.Activities.log
import org.json.JSONObject
import java.io.File

object ModelFileUtils {

    fun readBounds(file: File): Triple<Float, Float, Float>? {
        return readBoundsFromBytes(
            runCatching { file.readBytes() }.getOrNull() ?: return null,
            file.name
        )
    }
    fun readBounds(context: Context, assetPath: String): Triple<Float, Float, Float>? {
        return readBoundsFromBytes(
            runCatching { context.assets.open(assetPath).use { it.readBytes() } }
                .getOrNull() ?: return null,
            assetPath
        )
    }


    private fun readBoundsFromBytes(
        bytes: ByteArray,
        label: String
    ): Triple<Float, Float, Float>? {
        if (bytes.size < 20) return null
        return try {
            val jsonLen = readInt32LE(bytes, 12)
            val jsonStr = String(bytes, 20, minOf(jsonLen, bytes.size - 20), Charsets.UTF_8)
            val json      = JSONObject(jsonStr)
            val accessors = json.optJSONArray("accessors") ?: return null
            val nodes     = json.optJSONArray("nodes")     ?: return null
            val meshes    = json.optJSONArray("meshes")    ?: return null

            val children  = mutableMapOf<Int, List<Int>>()
            val nodeMesh  = mutableMapOf<Int, Int>()

            for (ni in 0 until nodes.length()) {
                val node = nodes.getJSONObject(ni)
                val childArr = node.optJSONArray("children")
                if (childArr != null) {
                    children[ni] = (0 until childArr.length()).map { childArr.getInt(it) }
                }
                if (node.has("mesh")) nodeMesh[ni] = node.getInt("mesh")
            }

            val allChildren = children.values.flatten().toSet()
            val roots = (0 until nodes.length()).filter { it !in allChildren }
            data class Vec3(val x: Float, val y: Float, val z: Float)

            val meshGlobalScale = mutableMapOf<Int, Vec3>()

            fun dfs(nodeIdx: Int, parentScale: Vec3) {
                val node = nodes.getJSONObject(nodeIdx)
                val scaleArr = node.optJSONArray("scale")
                val localScale = if (scaleArr != null && scaleArr.length() >= 3) {
                    Vec3(
                        scaleArr.getDouble(0).toFloat() * parentScale.x,
                        scaleArr.getDouble(1).toFloat() * parentScale.y,
                        scaleArr.getDouble(2).toFloat() * parentScale.z
                    )
                } else parentScale

                nodeMesh[nodeIdx]?.let { meshIdx ->
                    meshGlobalScale.getOrPut(meshIdx) { localScale }
                }

                children[nodeIdx]?.forEach { child -> dfs(child, localScale) }
            }

            roots.forEach { dfs(it, Vec3(1f, 1f, 1f)) }

            var minX = Float.MAX_VALUE;  var minY = Float.MAX_VALUE;  var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var found = false

            for (mi in 0 until meshes.length()) {
                val scale = meshGlobalScale[mi] ?: Vec3(1f, 1f, 1f)
                val primitives = meshes.getJSONObject(mi).optJSONArray("primitives") ?: continue
                for (pi in 0 until primitives.length()) {
                    val posIdx = primitives.getJSONObject(pi)
                        .optJSONObject("attributes")
                        ?.optInt("POSITION", -1)
                        ?.takeIf { it >= 0 } ?: continue
                    if (posIdx >= accessors.length()) continue
                    val acc    = accessors.getJSONObject(posIdx)
                    if (acc.optString("type") != "VEC3") continue
                    val minArr = acc.optJSONArray("min") ?: continue
                    val maxArr = acc.optJSONArray("max") ?: continue
                    if (minArr.length() < 3 || maxArr.length() < 3) continue

                    minX = minOf(minX, minArr.getDouble(0).toFloat() * scale.x)
                    minY = minOf(minY, minArr.getDouble(1).toFloat() * scale.y)
                    minZ = minOf(minZ, minArr.getDouble(2).toFloat() * scale.z)
                    maxX = maxOf(maxX, maxArr.getDouble(0).toFloat() * scale.x)
                    maxY = maxOf(maxY, maxArr.getDouble(1).toFloat() * scale.y)
                    maxZ = maxOf(maxZ, maxArr.getDouble(2).toFloat() * scale.z)
                    found = true
                }
            }

            if (!found) return null

            val rawW = maxX - minX
            val rawH = maxY - minY
            val rawD = maxZ - minZ

            val w = rawW.coerceAtLeast(0.001f)
            val h = rawH.coerceAtLeast(0.001f)
            val d = rawD.coerceAtLeast(0.001f)
            log("GLB bounds [$label]: ${w}×${h}×${d} m")
            Triple(w, h, d)

        } catch (e: Exception) {
            log("readBounds FAILED [$label]: ${e.message}")
            null
        }
    }

    private fun readInt32LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
                ((b[offset+1].toInt() and 0xFF) shl 8) or
                ((b[offset+2].toInt() and 0xFF) shl 16) or
                ((b[offset+3].toInt() and 0xFF) shl 24)
}