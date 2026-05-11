#include <jni.h>
#include <string>
#include <fstream>
#include <algorithm>
#include <android/log.h>

// Assimp headers — available after CMake fetches the AAR/prebuilt
#include <assimp/Importer.hpp>
#include <assimp/Exporter.hpp>
#include <assimp/scene.h>
#include <assimp/postprocess.h>

#define LOG_TAG "GlbConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Format detection ──────────────────────────────────────────────────────────
// Returns the lowercased file extension including the dot, e.g. ".stl", ".fbx"
static std::string getExtension(const std::string& path) {
    const size_t dot = path.rfind('.');
    if (dot == std::string::npos) return "";
    std::string ext = path.substr(dot);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    return ext;
}

// Supported input formats understood by this converter.
// .blend and .max are intentionally excluded — see notes below.
enum class InputFormat {
    STL,   // ✅ Fully supported
    FBX,   // ✅ Fully supported via Assimp's FBX importer
    OBJ,   // ✅ Fully supported (was already working)
    OTHER, // ✅ Passed through to Assimp as-is (best-effort)
    UNSUPPORTED // ❌ Known-bad formats — rejected early with a clear error
};

static InputFormat detectFormat(const std::string& path) {
    const std::string ext = getExtension(path);
    if (ext == ".stl")   return InputFormat::STL;
    if (ext == ".fbx")   return InputFormat::FBX;
    if (ext == ".obj")   return InputFormat::OBJ;
    // .blend: Assimp's importer only handles older Blender formats and is
    // unreliable on modern files. Requires Blender's Python runtime for
    // anything non-trivial — not available in an Android JNI context.
    if (ext == ".blend") return InputFormat::UNSUPPORTED;
    // .max: Autodesk 3ds Max's native format is proprietary and undocumented.
    // No open-source library reads it. Users should export to FBX from 3ds Max.
    if (ext == ".max")   return InputFormat::UNSUPPORTED;
    return InputFormat::OTHER;
}

// ── Default PBR material injection ───────────────────────────────────────────
// Formats without embedded material data (STL, some FBX) get a grey matte
// PBR material so they render correctly in SceneView / Filament.
static bool needsDefaultMaterial(const aiScene* scene) {
    return scene->mNumMaterials == 0;
}

static void injectDefaultPbrMaterial(aiScene* scene) {
    LOGI("Injecting default PBR material (scene had no materials)");

    auto* mat = new aiMaterial();

    aiColor4D baseColor(0.7f, 0.7f, 0.7f, 1.0f);
    mat->AddProperty(&baseColor, 1, AI_MATKEY_BASE_COLOR);

    float metallic  = 0.0f;
    float roughness = 0.6f;
    mat->AddProperty(&metallic,  1, AI_MATKEY_METALLIC_FACTOR);
    mat->AddProperty(&roughness, 1, AI_MATKEY_ROUGHNESS_FACTOR);

    delete[] scene->mMaterials;
    scene->mMaterials    = new aiMaterial*[1]{ mat };
    scene->mNumMaterials = 1;

    for (unsigned int i = 0; i < scene->mNumMeshes; i++) {
        scene->mMeshes[i]->mMaterialIndex = 0;
    }
}

// ── Mesh centring ─────────────────────────────────────────────────────────────
// Bakes a centring translation so the model sits with its pivot at origin and
// its bottom face on Y=0.  SceneView ring handles and AR anchor placement both
// assume the pivot equals the bounding-box centre.
static void centreMeshes(aiScene* scene) {
    float minX =  1e30f, minY =  1e30f, minZ =  1e30f;
    float maxX = -1e30f, maxY = -1e30f, maxZ = -1e30f;

    for (unsigned int m = 0; m < scene->mNumMeshes; m++) {
        const aiMesh* mesh = scene->mMeshes[m];
        for (unsigned int v = 0; v < mesh->mNumVertices; v++) {
            const aiVector3D& vtx = mesh->mVertices[v];
            if (vtx.x < minX) minX = vtx.x;  if (vtx.x > maxX) maxX = vtx.x;
            if (vtx.y < minY) minY = vtx.y;  if (vtx.y > maxY) maxY = vtx.y;
            if (vtx.z < minZ) minZ = vtx.z;  if (vtx.z > maxZ) maxZ = vtx.z;
        }
    }

    const float shiftX = -(minX + maxX) * 0.5f;
    const float shiftY = -minY;   // push bottom face to Y=0 so it sits on the floor plane
    const float shiftZ = -(minZ + maxZ) * 0.5f;

    if (shiftX == 0.0f && shiftY == 0.0f && shiftZ == 0.0f) return;

    LOGI("Centring mesh: shift (%.4f, %.4f, %.4f)", shiftX, shiftY, shiftZ);
    for (unsigned int m = 0; m < scene->mNumMeshes; m++) {
        aiMesh* mesh = scene->mMeshes[m];
        for (unsigned int v = 0; v < mesh->mNumVertices; v++) {
            mesh->mVertices[v].x += shiftX;
            mesh->mVertices[v].y += shiftY;
            mesh->mVertices[v].z += shiftZ;
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_pracazaliczeniowa_converter_GlbConverter_nativeConvert(
        JNIEnv* env,
        jobject /* this */,
        jstring inputPathJ,
        jstring outputPathJ) {

    const char* inputPath  = env->GetStringUTFChars(inputPathJ,  nullptr);
    const char* outputPath = env->GetStringUTFChars(outputPathJ, nullptr);

    LOGI("Converting: %s → %s", inputPath, outputPath);

    const InputFormat fmt = detectFormat(std::string(inputPath));

    // ── Reject known-unsupported formats early ────────────────────────────────
    if (fmt == InputFormat::UNSUPPORTED) {
        const std::string ext = getExtension(std::string(inputPath));
        if (ext == ".blend") {
            LOGE("Unsupported format: .blend — Assimp's Blender importer is "
                 "incomplete for modern files and requires Blender's Python "
                 "runtime, which is unavailable on Android. "
                 "Export to FBX or OBJ from Blender first.");
        } else if (ext == ".max") {
            LOGE("Unsupported format: .max — 3ds Max's native format is "
                 "proprietary and unreadable by any open-source library. "
                 "Export to FBX from 3ds Max first.");
        } else {
            LOGE("Unsupported format: %s", ext.c_str());
        }
        env->ReleaseStringUTFChars(inputPathJ,  inputPath);
        env->ReleaseStringUTFChars(outputPathJ, outputPath);
        return JNI_FALSE;
    }

    Assimp::Importer importer;

    // ── Per-format importer configuration ────────────────────────────────────
    //
    // STL — CAD/Blender exports use millimetres; glTF expects metres.
    //        aiProcess_GlobalScale multiplies every vertex by the factor below.
    //
    // FBX — Autodesk FBX can embed its own unit metadata (centimetres is common
    //        from Maya; centimetres or metres from Blender).  Assimp reads the
    //        FBX unit info and exposes it via AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY
    //        automatically when aiProcess_GlobalScale is requested, so we
    //        enable the flag unconditionally for FBX and let Assimp do the right
    //        thing.  If the FBX has no unit metadata the scale factor defaults
    //        to 1.0 (no-op), which is safe.
    //
    // OBJ / OTHER — no unit convention; leave scale at default 1.0.

    const bool applyGlobalScale = (fmt == InputFormat::STL || fmt == InputFormat::FBX);

    if (fmt == InputFormat::STL) {
        importer.SetPropertyFloat(AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 0.001f);
        LOGI("STL input — applying 0.001 global scale (mm → m)");
    } else if (fmt == InputFormat::FBX) {
        // Let Assimp derive the scale from the FBX unit metadata.
        // Setting the property to 1.0 keeps it a no-op when metadata is absent.
        importer.SetPropertyFloat(AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 1.0f);
        LOGI("FBX input — GlobalScale enabled (Assimp reads embedded unit metadata)");
    }

    // ── Post-processing flags ─────────────────────────────────────────────────
    //   Triangulate              glTF only supports triangles
    //   GenSmoothNormals         STL / some FBX files lack normals
    //   FlipUVs                  glTF expects top-left UV origin
    //   JoinIdenticalVertices    de-duplicates OBJ / FBX verts
    //   ValidateDataStructure    catch malformed input early
    //   GlobalScale              bake AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY (STL/FBX)
    //   PopulateArmatureData     needed for FBX skeletal animations
    //   LimitBoneWeights         glTF allows max 4 weights per vertex
    const unsigned int ppFlags =
            aiProcess_Triangulate            |
                    aiProcess_GenSmoothNormals       |
                    aiProcess_FlipUVs                |
                    aiProcess_JoinIdenticalVertices  |
                    aiProcess_ValidateDataStructure  |
                    (applyGlobalScale ? aiProcess_GlobalScale        : 0u) |
                    (fmt == InputFormat::FBX ? aiProcess_PopulateArmatureData : 0u) |
                    (fmt == InputFormat::FBX ? aiProcess_LimitBoneWeights     : 0u);

    const aiScene* scene = importer.ReadFile(inputPath, ppFlags);

    if (!scene || scene->mFlags & AI_SCENE_FLAGS_INCOMPLETE || !scene->mRootNode) {
        LOGE("Assimp import failed: %s", importer.GetErrorString());
        env->ReleaseStringUTFChars(inputPathJ,  inputPath);
        env->ReleaseStringUTFChars(outputPathJ, outputPath);
        return JNI_FALSE;
    }

    // ReadFile returns const aiScene*; we need to mutate it for material
    // injection and centring.  This is safe: Assimp owns the allocation and
    // we operate entirely within the same importer lifetime before export.
    aiScene* mutableScene = const_cast<aiScene*>(scene);

    // ── Inject a default PBR material when the scene has none ────────────────
    // STL never carries material data.  FBX files sometimes do, sometimes don't.
    // OBJ files carry a .mtl sidecar that Assimp populates.
    // We check mNumMaterials == 0 universally rather than gating on the format.
    if (needsDefaultMaterial(scene)) {
        injectDefaultPbrMaterial(mutableScene);
    }

    // ── Centre all meshes at origin ───────────────────────────────────────────
    centreMeshes(mutableScene);

    // ── Export as binary glTF (.glb) ──────────────────────────────────────────
    Assimp::Exporter exporter;
    aiReturn result = exporter.Export(scene, "glb2", outputPath);

    env->ReleaseStringUTFChars(inputPathJ,  inputPath);
    env->ReleaseStringUTFChars(outputPathJ, outputPath);

    if (result != aiReturn_SUCCESS) {
        LOGE("Assimp export failed: %s", exporter.GetErrorString());
        return JNI_FALSE;
    }

    LOGI("Conversion successful → %s", outputPath);
    return JNI_TRUE;
}