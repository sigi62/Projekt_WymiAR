#include <jni.h>
#include <string>
#include <fstream>
#include <algorithm>
#include <android/log.h>

#include <assimp/Importer.hpp>
#include <assimp/Exporter.hpp>
#include <assimp/scene.h>
#include <assimp/postprocess.h>

#define LOG_TAG "GlbConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct JStringGuard {
    JNIEnv*     env;
    jstring     jstr;
    const char* cstr;

    JStringGuard(JNIEnv* e, jstring j)
            : env(e), jstr(j), cstr(e->GetStringUTFChars(j, nullptr)) {}

    ~JStringGuard() {
        if (cstr) env->ReleaseStringUTFChars(jstr, cstr);
    }

    operator const char*() const { return cstr; }

    JStringGuard(const JStringGuard&)            = delete;
    JStringGuard& operator=(const JStringGuard&) = delete;
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
static std::string getExtension(const std::string& path) {
    const size_t dot = path.rfind('.');
    if (dot == std::string::npos) return "";
    std::string ext = path.substr(dot);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    return ext;
}

enum class InputFormat {
    STL, FBX, OBJ, DAE, GLTF, THRDS, PLY, OTHER, UNSUPPORTED
};

static InputFormat detectFormat(const std::string& path) {
    const std::string ext = getExtension(path);
    if (ext == ".stl")   return InputFormat::STL;
    if (ext == ".fbx")   return InputFormat::FBX;
    if (ext == ".obj")   return InputFormat::OBJ;
    if (ext == ".dae")   return InputFormat::DAE;
    if (ext == ".gltf")  return InputFormat::GLTF;
    if (ext == ".3ds")   return InputFormat::THRDS;
    if (ext == ".ply")   return InputFormat::PLY;
    if (ext == ".blend") return InputFormat::UNSUPPORTED;
    if (ext == ".max")   return InputFormat::UNSUPPORTED;
    return InputFormat::OTHER;
}

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

    for (unsigned int i = 0; i < scene->mNumMaterials; ++i) {
        delete scene->mMaterials[i];
        scene->mMaterials[i] = nullptr;
    }
    delete[] scene->mMaterials;

    scene->mMaterials    = new aiMaterial*[1]{ mat };
    scene->mNumMaterials = 1;

    for (unsigned int i = 0; i < scene->mNumMeshes; i++) {
        scene->mMeshes[i]->mMaterialIndex = 0;
    }
}

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
    const float shiftY = -minY;
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

// ---------------------------------------------------------------------------
// JNI entry point
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_WymiAR_converter_GlbConverter_nativeConvert(
        JNIEnv* env,
        jobject,
        jstring inputPathJ,
        jstring outputPathJ) {

    JStringGuard inputPath (env, inputPathJ);
    JStringGuard outputPath(env, outputPathJ);

    if (!inputPath.cstr || !outputPath.cstr) {
        LOGE("GetStringUTFChars returned nullptr — out of memory");
        return JNI_FALSE;
    }

    LOGI("Converting: %s -> %s", inputPath.cstr, outputPath.cstr);

    const InputFormat fmt = detectFormat(std::string(inputPath.cstr));

    if (fmt == InputFormat::UNSUPPORTED) {
        const std::string ext = getExtension(std::string(inputPath.cstr));
        if (ext == ".blend") {
            LOGE("Unsupported format: .blend — Assimp's Blender importer is incomplete...");
        } else if (ext == ".max") {
            LOGE("Unsupported format: .max — 3ds Max's native format is proprietary...");
        } else {
            LOGE("Unsupported format: %s", ext.c_str());
        }
        return JNI_FALSE;
    }

    Assimp::Importer importer;

    const bool applyGlobalScale = (fmt == InputFormat::STL  ||
            fmt == InputFormat::FBX  ||
            fmt == InputFormat::DAE  ||
            fmt == InputFormat::THRDS);

    if (fmt == InputFormat::STL) {
        importer.SetPropertyFloat(AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 0.001f);
    } else if (fmt == InputFormat::FBX) {
        importer.SetPropertyFloat(AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 1.0f);
    } else if (fmt == InputFormat::DAE) {
        importer.SetPropertyFloat(AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 1.0f);
    } else if (fmt == InputFormat::THRDS) {
        importer.SetPropertyFloat(AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 0.001f);
    }

    const bool hasSkeleton = (fmt == InputFormat::FBX || fmt == InputFormat::DAE);

    importer.SetPropertyBool(AI_CONFIG_PP_FD_REMOVE, true);

    const unsigned int ppFlags =
            aiProcess_Triangulate              |
                    aiProcess_GenSmoothNormals         |
                    aiProcess_FlipUVs                  |
                    aiProcess_JoinIdenticalVertices    |
                    aiProcess_ValidateDataStructure    |
                    aiProcess_RemoveRedundantMaterials |
                    aiProcess_OptimizeMeshes           |
                    aiProcess_ImproveCacheLocality     |
                    aiProcess_FindDegenerates          |
                    (applyGlobalScale ? aiProcess_GlobalScale          : 0u) |
                    (hasSkeleton      ? aiProcess_PopulateArmatureData : 0u) |
                    (hasSkeleton      ? aiProcess_LimitBoneWeights     : 0u);

    const aiScene* scene = importer.ReadFile(inputPath.cstr, ppFlags);

    if (!scene || scene->mFlags & AI_SCENE_FLAGS_INCOMPLETE || !scene->mRootNode) {
        LOGE("Assimp import failed: %s", importer.GetErrorString());
        return JNI_FALSE;
    }

    aiScene* mutableScene = const_cast<aiScene*>(scene);

    if (needsDefaultMaterial(scene)) {
        injectDefaultPbrMaterial(mutableScene);
    }

    centreMeshes(mutableScene);

    Assimp::Exporter exporter;
    aiReturn result = exporter.Export(scene, "glb2", outputPath.cstr);

    importer.FreeScene();

    if (result != aiReturn_SUCCESS) {
        LOGE("Assimp export failed: %s", exporter.GetErrorString());
    }

    LOGI("Conversion successful -> %s", outputPath.cstr);
    return JNI_TRUE;
}