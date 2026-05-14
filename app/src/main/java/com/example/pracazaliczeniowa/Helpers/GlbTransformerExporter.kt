package com.example.pracazaliczeniowa.Helpers

import com.example.pracazaliczeniowa.Managers.ModelProfile
import com.example.pracazaliczeniowa.Activities.log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a [com.example.pracazaliczeniowa.Managers.ModelProfile]'s scale and rotation into the root node transform
 * of a GLB file by surgically splicing into the JSON chunk.
 *
 * We deliberately avoid re-serialising the whole JSON through org.json because:
 *  - org.json does not guarantee key ordering, which can confuse some renderers
 *  - base64 image data URIs embedded in the JSON survive intact (no string re-encoding)
 *  - buffer/bufferView byte offsets are never accidentally perturbed
 *
 * The BIN chunk is copied byte-for-byte; only the JSON chunk changes.
 */
object GlbTransformExporter {

    private const val GLB_MAGIC       = 0x46546C67  // "glTF"
    private const val CHUNK_TYPE_JSON = 0x4E4F534A  // "JSON"

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

            // ── Extract JSON chunk ────────────────────────────────────────────
            val jsonChunkLen = buf.getInt(12)            // padded length, per spec
            val jsonRaw      = bytes.copyOfRange(20, 20 + jsonChunkLen)
            // Trim trailing space-padding before parsing so our string ops are clean
            val jsonTrimmed = jsonRaw.dropLastWhile { it == 0x20.toByte() }.toByteArray()
            var jsonStr      = String(jsonTrimmed, Charsets.UTF_8)

            // ── Find the root node index from the scene ───────────────────────
            val rootNodeIdx = findRootNodeIndex(jsonStr, label) ?: return null

            // ── Splice scale + rotation into that node object ─────────────────
            jsonStr = spliceNodeTransform(jsonStr, rootNodeIdx, profile, label) ?: return null

            // ── Re-pad JSON to 4-byte boundary with spaces ────────────────────
            val newJsonBytes    = jsonStr.toByteArray(Charsets.UTF_8)
            val paddedLen       = (newJsonBytes.size + 3) and 3.inv()
            val paddingNeeded   = paddedLen - newJsonBytes.size

            // ── BIN chunk: copy verbatim from just after the original JSON chunk
            val binStart     = 20 + jsonChunkLen
            val binChunkData = if (binStart < bytes.size) bytes.copyOfRange(binStart, bytes.size)
            else ByteArray(0)

            // ── Reassemble ────────────────────────────────────────────────────
            val totalLen = 12 + 8 + paddedLen + binChunkData.size
            val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

            // Header
            out.putInt(GLB_MAGIC)
            out.putInt(2)
            out.putInt(totalLen)

            // JSON chunk
            out.putInt(paddedLen)
            out.putInt(CHUNK_TYPE_JSON)
            out.put(newJsonBytes)
            repeat(paddingNeeded) { out.put(0x20) }

            // BIN chunk verbatim (includes its own 8-byte header + data)
            if (binChunkData.isNotEmpty()) out.put(binChunkData)

            log("GlbExport SUCCESS [$label]: ${bytes.size}→$totalLen bytes")
            out.array()

        } catch (e: Exception) {
            log("GlbExport FAILED [$label]: ${e.message}")
            null
        }
    }

    // ── Find the index of the scene's first root node ─────────────────────────

    private fun findRootNodeIndex(json: String, label: String): Int? {
        // Fast path: find "scene": N, then scenes[N].nodes[0]
        // We use simple regex on the JSON string to avoid a full re-parse
        // while still being robust to whitespace variation.
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

    // ── Splice scale + rotation fields into node[rootNodeIdx] ─────────────────
    //
    // Strategy: serialise just the transform fields we want to inject, then
    // find the exact character position of node[rootNodeIdx] in the JSON string
    // and insert/replace only those keys. Every other character is untouched.

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

            // ✅ Read the node's existing (native/baked) scale, default to [1,1,1]
            val existingScale = node.optJSONArray("scale")
            val nativeX = existingScale?.optDouble(0, 1.0)?.toFloat() ?: 1f
            val nativeY = existingScale?.optDouble(1, 1.0)?.toFloat() ?: 1f
            val nativeZ = existingScale?.optDouble(2, 1.0)?.toFloat() ?: 1f


            // ✅ Multiply profile scale ON TOP of native scale
            node.put("scale", org.json.JSONArray().apply {
                put((nativeX * profile.scaleX).toDouble())
                put((nativeY * profile.scaleY).toDouble())
                put((nativeZ * profile.scaleZ).toDouble())
            })

            val localQuat = eulerDegreesToQuaternion(profile.rotationX, 0f, profile.rotationZ)
            val yawQuat   = eulerDegreesToQuaternion(0f, profile.rotationY, 0f)
            val quat      = multiplyQuaternions(yawQuat, localQuat)  // yaw * local

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

    /**
     * Finds the JSON object at `nodes[nodeIdx]` in the raw [jsonStr] and
     * replaces it with [newNodeJson], returning the modified string.
     *
     * We walk the string character-by-character to find the node's `{…}` span,
     * which correctly handles nested objects and arrays inside the node without
     * depending on key ordering or pretty-printing.
     */
    private fun replaceNodeInJsonString(
        jsonStr: String,
        nodeIdx: Int,
        newNodeJson: String
    ): String? {
        // Find the start of the "nodes" array value
        val nodesKey   = "\"nodes\""
        var searchFrom = 0

        // There can be multiple "nodes" keys (e.g. scenes also have "nodes").
        // We want the top-level one. Find the first occurrence that is followed
        // by '[' (the array open bracket), skipping scene "nodes" arrays which
        // may appear later. The top-level "nodes" array in a GLB always comes
        // before "scenes" alphabetically in Assimp output, but we can't rely on
        // ordering — instead find ALL occurrences and pick the one whose array
        // contains the highest node indices (i.e. is the full node list).
        // Simpler robust approach: find the "nodes" key that is a direct child
        // of the root object by counting brace depth.
        val nodesArrayStart = findTopLevelNodesArray(jsonStr) ?: return null

        // Now skip nodeIdx elements inside this array to find our node's '{'
        var arrayPos = nodesArrayStart + 1  // skip '['
        var currentIdx = 0
        while (currentIdx < nodeIdx) {
            // Skip to the next '{' at depth 0 within the array
            arrayPos = skipToNextObject(jsonStr, arrayPos) ?: return null
            // Skip past this entire object
            arrayPos = skipObject(jsonStr, arrayPos) ?: return null
            // Skip comma/whitespace
            while (arrayPos < jsonStr.length && jsonStr[arrayPos] in " \t\n\r,") arrayPos++
            currentIdx++
        }

        // Skip to the node's opening '{'
        arrayPos = skipToNextObject(jsonStr, arrayPos) ?: return null
        val nodeStart = arrayPos
        val nodeEnd   = skipObject(jsonStr, nodeStart) ?: return null

        // Replace the node span
        return jsonStr.substring(0, nodeStart) + newNodeJson + jsonStr.substring(nodeEnd)
    }


    private fun multiplyQuaternions(a: FloatArray, b: FloatArray): FloatArray {
        // a = [ax, ay, az, aw], b = [bx, by, bz, bw]
        // result = a * b  (applies b first, then a)
        return floatArrayOf(
            a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
            a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
            a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
            a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
        )
    }
    /** Returns the index of '[' that opens the top-level "nodes" array. */
    private fun findTopLevelNodesArray(json: String): Int? {
        var depth = 0
        var i = 0
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    // Check if this is "nodes" key at root depth (depth == 1)
                    if (depth == 1 && json.startsWith("\"nodes\"", i)) {
                        // Find the ':' then the '['
                        var j = i + 7  // skip past "nodes"
                        while (j < json.length && json[j] in " \t\n\r:") j++
                        if (j < json.length && json[j] == '[') return j
                    }
                    // Skip the whole string token
                    i = skipString(json, i) ?: return null
                    continue
                }
            }
            i++
        }
        return null
    }

    /** Returns the index just past the closing `}` of the object starting at [start]. */
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

    /** Skips whitespace/commas and returns the index of the next `{`. */
    private fun skipToNextObject(json: String, from: Int): Int? {
        var i = from
        while (i < json.length) {
            if (json[i] == '{') return i
            if (json[i] == ']') return null   // end of array, node not found
            i++
        }
        return null
    }

    /** Returns the index just past the closing `"` of the string starting at [start]. */
    private fun skipString(json: String, start: Int): Int? {
        var i = start + 1  // skip opening '"'
        while (i < json.length) {
            when (json[i]) {
                '\\'  -> i++             // skip escaped char
                '"'   -> return i + 1
            }
            i++
        }
        return null
    }

    // ── Euler XYZ degrees → quaternion [x, y, z, w] ──────────────────────────

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