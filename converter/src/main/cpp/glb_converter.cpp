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

// Returns true for any capitalisation of ".stl"
static bool hasStlExtension(const std::string& path) {
    if (path.size() < 4) return false;
    std::string suffix = path.substr(path.size() - 4);
    std::transform(suffix.begin(), suffix.end(), suffix.begin(), ::tolower);
    return suffix == ".stl";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_pracazaliczeniowa_converter_GlbConverter_nativeConvert(
        JNIEnv* env,
        jobject /* this */,
        jstring inputPathJ,
        jstring outputPathJ) {

    const char* inputPath  = env->GetStringUTFChars(inputPathJ,  nullptr);
    const char* outputPath = env->GetStringUTFChars(outputPathJ, nullptr);

    LOGI("Converting: %s → %s", inputPath, outputPath);

    Assimp::Importer importer;

    // STL files from Blender/CAD tools use millimetres; glTF expects metres.
    // Set the scale factor BEFORE ReadFile, then aiProcess_GlobalScale bakes
    // it into every vertex position during import.
    const bool stlInput = hasStlExtension(std::string(inputPath));
    if (stlInput) {
        importer.SetPropertyFloat(AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 0.001f);
        LOGI("STL input -- applying 0.001 global scale (mm -> m)");
    }

    // Post-processing flags:
    //   Triangulate             -- glTF only supports triangles
    //   GenSmoothNormals        -- STL files often lack normals
    //   FlipUVs                 -- glTF expects top-left UV origin
    //   JoinIdenticalVertices   -- de-duplicates OBJ verts
    //   GlobalScale             -- applies AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY (STL only)
    const unsigned int ppFlags =
            aiProcess_Triangulate           |
            aiProcess_GenSmoothNormals      |
            aiProcess_FlipUVs               |
            aiProcess_JoinIdenticalVertices |
            aiProcess_ValidateDataStructure |
            (stlInput ? aiProcess_GlobalScale : 0u);

    const aiScene* scene = importer.ReadFile(inputPath, ppFlags);

    if (!scene || scene->mFlags & AI_SCENE_FLAGS_INCOMPLETE || !scene->mRootNode) {
        LOGE("Assimp import failed: %s", importer.GetErrorString());
        env->ReleaseStringUTFChars(inputPathJ,  inputPath);
        env->ReleaseStringUTFChars(outputPathJ, outputPath);
        return JNI_FALSE;
    }

    // ── Assign a default PBR material for STL (no material data) ─────────────
    // OBJ files carry a .mtl sidecar so Assimp already populates materials.
    // STL never has material data, so Assimp produces mNumMaterials == 0.
    // We must CREATE a new material rather than patching an existing one,
    // then re-point every mesh at it.
    if (hasStlExtension(std::string(inputPath))) {
        LOGI("STL detected — injecting default PBR material");

        auto* mat = new aiMaterial();

        // Grey matte PBR defaults — render correctly in SceneView / Filament
        aiColor4D baseColor(0.7f, 0.7f, 0.7f, 1.0f);
        mat->AddProperty(&baseColor, 1, AI_MATKEY_BASE_COLOR);

        float metallic  = 0.0f;
        float roughness = 0.6f;
        mat->AddProperty(&metallic,  1, AI_MATKEY_METALLIC_FACTOR);
        mat->AddProperty(&roughness, 1, AI_MATKEY_ROUGHNESS_FACTOR);

        // ReadFile returns const aiScene* — cast away const to mutate material data.
        // This is safe: Assimp owns the allocation and we stay within the same
        // importer lifetime, before any export call.
        aiScene* mutableScene = const_cast<aiScene*>(scene);

        delete[] mutableScene->mMaterials;
        mutableScene->mMaterials    = new aiMaterial*[1]{ mat };
        mutableScene->mNumMaterials = 1;

        // Point every mesh at material index 0
        for (unsigned int i = 0; i < mutableScene->mNumMeshes; i++) {
            mutableScene->mMeshes[i]->mMaterialIndex = 0;
        }
    }

    // ── Centre all meshes at origin ───────────────────────────────────────────
    // Models exported from Blender/CAD tools are often not origin-centred:
    // their bounding box centre can be far from (0,0,0). SceneView's ring
    // handle and anchor placement both assume the pivot is at the mesh centre,
    // so we bake a centring translation into every mesh's vertices here.
    {
        aiScene* s = const_cast<aiScene*>(scene);

        // 1. Compute the global AABB across all meshes
        float minX =  1e30f, minY =  1e30f, minZ =  1e30f;
        float maxX = -1e30f, maxY = -1e30f, maxZ = -1e30f;
        for (unsigned int m = 0; m < s->mNumMeshes; m++) {
            const aiMesh* mesh = s->mMeshes[m];
            for (unsigned int v = 0; v < mesh->mNumVertices; v++) {
                const aiVector3D& vtx = mesh->mVertices[v];
                if (vtx.x < minX) minX = vtx.x;  if (vtx.x > maxX) maxX = vtx.x;
                if (vtx.y < minY) minY = vtx.y;  if (vtx.y > maxY) maxY = vtx.y;
                if (vtx.z < minZ) minZ = vtx.z;  if (vtx.z > maxZ) maxZ = vtx.z;
            }
        }

        // 2. Shift = -centre of bounding box (XZ), keep Z base at 0 (bottom on floor)
        float shiftX = -(minX + maxX) * 0.5f;
        float shiftY = -minY;          // push bottom face to Y=0 so it sits on the plane
        float shiftZ = -(minZ + maxZ) * 0.5f;

        if (shiftX != 0.0f || shiftY != 0.0f || shiftZ != 0.0f) {
            LOGI("Centring mesh: shift (%.4f, %.4f, %.4f)", shiftX, shiftY, shiftZ);
            for (unsigned int m = 0; m < s->mNumMeshes; m++) {
                aiMesh* mesh = s->mMeshes[m];
                for (unsigned int v = 0; v < mesh->mNumVertices; v++) {
                    mesh->mVertices[v].x += shiftX;
                    mesh->mVertices[v].y += shiftY;
                    mesh->mVertices[v].z += shiftZ;
                }
            }
        }
    }

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