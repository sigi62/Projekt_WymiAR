package com.example.WymiAR.Helpers

import com.example.WymiAR.Managers.ModelProfile
import com.example.WymiAR.Activities.log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GlbTransformExporter {

    private const val GLB_MAGIC       = 0x46546C67
    private const val CHUNK_TYPE_JSON = 0x4E4F534A

    fun applyProfileToGlb(sourceFile: File, profile: ModelProfile): ByteArray? {
        val bytes = runCatching { sourceFile.readBytes() }.getOrElse {
            log("GlbExport FAILED: cannot read ${sourceFile.name}: ${it.message}")
            return null
        }
        return applyProfileToBytes(bytes, sourceFile.name, profile)
    }

    fun applyProfileToBytes(
        bytes: ByteArray,
        label: String,
        profile: ModelProfile
    ): ByteArray? {
        if (bytes.size < 28) {
            log("GlbExport FAILED [$label]: file too small")
            return null
        }
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            if (buf.getInt(0) != GLB_MAGIC) {
                log("GlbExport FAILED [$label]: not a GLB file")
                return null
            }
            if (buf.getInt(16) != CHUNK_TYPE_JSON) {
                log("GlbExport FAILED [$label]: first chunk is not JSON")
                return null
            }

            val jsonChunkLen = buf.getInt(12)
            val jsonRaw      = bytes.copyOfRange(20, 20 + jsonChunkLen)
            val jsonTrimmed = jsonRaw.dropLastWhile { it == 0x20.toByte() }.toByteArray()
            var jsonStr      = String(jsonTrimmed, Charsets.UTF_8)

            val rootNodeIdx = findRootNodeIndex(jsonStr, label) ?: return null

            jsonStr = spliceNodeTransform(jsonStr, rootNodeIdx, profile, label) ?: return null

            val newJsonBytes    = jsonStr.toByteArray(Charsets.UTF_8)
            val paddedLen       = (newJsonBytes.size + 3) and 3.inv()
            val paddingNeeded   = paddedLen - newJsonBytes.size

            val binStart     = 20 + jsonChunkLen
            val binChunkData = if (binStart < bytes.size) bytes.copyOfRange(binStart, bytes.size)
            else ByteArray(0)

            val totalLen = 12 + 8 + paddedLen + binChunkData.size
            val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)


            out.putInt(GLB_MAGIC)
            out.putInt(2)
            out.putInt(totalLen)

            out.putInt(paddedLen)
            out.putInt(CHUNK_TYPE_JSON)
            out.put(newJsonBytes)
            repeat(paddingNeeded) { out.put(0x20) }

            if (binChunkData.isNotEmpty()) out.put(binChunkData)

            log("GlbExport SUCCESS [$label]: ${bytes.size}→$totalLen bytes")
            out.array()

        } catch (e: Exception) {
            log("GlbExport FAILED [$label]: ${e.message}")
            null
        }
    }


    private fun findRootNodeIndex(json: String, label: String): Int? {
        return try {
            val obj     = org.json.JSONObject(json)
            val sceneIdx = obj.optInt("scene", 0)
            val scenes   = obj.optJSONArray("scenes")
            val nodes    = obj.optJSONArray("nodes")

            if (nodes == null || nodes.length() == 0) {
                log("GlbExport FAILED [$label]: no nodes")
                return null
            }

            scenes?.optJSONObject(sceneIdx)?.optJSONArray("nodes")?.optInt(0) ?: 0
        } catch (e: Exception) {
            log("GlbExport: could not find root node, defaulting to 0")
            0
        }
    }

    private fun spliceNodeTransform(
        json: String,
        nodeIdx: Int,
        profile: ModelProfile,
        label: String
    ): String? {
        return try {
            val root  = org.json.JSONObject(json)
            val nodes = root.getJSONArray("nodes")
            val node  = nodes.getJSONObject(nodeIdx)

            val existingScale = node.optJSONArray("scale")
            val nativeX = existingScale?.optDouble(0, 1.0)?.toFloat() ?: 1f
            val nativeY = existingScale?.optDouble(1, 1.0)?.toFloat() ?: 1f
            val nativeZ = existingScale?.optDouble(2, 1.0)?.toFloat() ?: 1f

            node.put("scale", org.json.JSONArray().apply {
                put((nativeX * profile.scaleX).toDouble())
                put((nativeY * profile.scaleY).toDouble())
                put((nativeZ * profile.scaleZ).toDouble())
            })

            val localQuat = eulerDegreesToQuaternion(profile.rotationX, 0f, profile.rotationZ)
            val yawQuat   = eulerDegreesToQuaternion(0f, profile.rotationY, 0f)
            val quat      = multiplyQuaternions(yawQuat, localQuat)

            node.put("rotation", org.json.JSONArray().apply {
                put(quat[0].toDouble())
                put(quat[1].toDouble())
                put(quat[2].toDouble())
                put(quat[3].toDouble())
            })
            nodes.put(nodeIdx, node)
            replaceNodeInJsonString(json, nodeIdx, node.toString())
        } catch (e: Exception) {
            log("GlbExport splice FAILED [$label]: ${e.message}")
            null
        }
    }

    private fun replaceNodeInJsonString(
        jsonStr: String,
        nodeIdx: Int,
        newNodeJson: String
    ): String? {

        val nodesArrayStart = findTopLevelNodesArray(jsonStr) ?: return null

        var arrayPos = nodesArrayStart + 1
        var currentIdx = 0
        while (currentIdx < nodeIdx) {
            arrayPos = skipToNextObject(jsonStr, arrayPos) ?: return null
            arrayPos = skipObject(jsonStr, arrayPos) ?: return null
            while (arrayPos < jsonStr.length && jsonStr[arrayPos] in " \t\n\r,") arrayPos++
            currentIdx++
        }

        arrayPos = skipToNextObject(jsonStr, arrayPos) ?: return null
        val nodeStart = arrayPos
        val nodeEnd   = skipObject(jsonStr, nodeStart) ?: return null

        return jsonStr.substring(0, nodeStart) + newNodeJson + jsonStr.substring(nodeEnd)
    }


    private fun multiplyQuaternions(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
            a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
            a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
            a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
        )
    }
    private fun findTopLevelNodesArray(json: String): Int? {
        var depth = 0
        var i = 0
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    if (depth == 1 && json.startsWith("\"nodes\"", i)) {
                        var j = i + 7
                        while (j < json.length && json[j] in " \t\n\r:") j++
                        if (j < json.length && json[j] == '[') return j
                    }
                    i = skipString(json, i) ?: return null
                    continue
                }
            }
            i++
        }
        return null
    }

    private fun skipObject(json: String, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < json.length) {
            when (json[i]) {
                '"'  -> { i = skipString(json, i) ?: return null; continue }
                '{'  -> depth++
                '}'  -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return null
    }

    private fun skipToNextObject(json: String, from: Int): Int? {
        var i = from
        while (i < json.length) {
            if (json[i] == '{') return i
            if (json[i] == ']') return null
            i++
        }
        return null
    }

    private fun skipString(json: String, start: Int): Int? {
        var i = start + 1
        while (i < json.length) {
            when (json[i]) {
                '\\'  -> i++
                '"'   -> return i + 1
            }
            i++
        }
        return null
    }

    private fun eulerDegreesToQuaternion(xDeg: Float, yDeg: Float, zDeg: Float): FloatArray {
        val toRad = (Math.PI / 180.0).toFloat()
        val hx = xDeg * toRad / 2f;  val cx = Math.cos(hx.toDouble()).toFloat(); val sx = Math.sin(hx.toDouble()).toFloat()
        val hy = yDeg * toRad / 2f;  val cy = Math.cos(hy.toDouble()).toFloat(); val sy = Math.sin(hy.toDouble()).toFloat()
        val hz = zDeg * toRad / 2f;  val cz = Math.cos(hz.toDouble()).toFloat(); val sz = Math.sin(hz.toDouble()).toFloat()
        return floatArrayOf(
            sx * cy * cz + cx * sy * sz,
            cx * sy * cz - sx * cy * sz,
            cx * cy * sz + sx * sy * cz,
            cx * cy * cz - sx * sy * sz
        )
    }
}