#include <jni.h>
#include <string>
#include <fstream>
#include <android/log.h>

// Assimp headers — available after CMake fetches the AAR/prebuilt
#include <assimp/Importer.hpp>
#include <assimp/Exporter.hpp>
#include <assimp/scene.h>
#include <assimp/postprocess.h>

#define LOG_TAG "GlbConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

    // Post-processing flags:
    //   Triangulate       — glTF only supports triangles
    //   GenNormals        — STL files often lack normals
    //   FlipUVs           — glTF expects top-left UV origin
    //   JoinIdenticalVertices — de-duplicates OBJ verts
    const aiScene* scene = importer.ReadFile(inputPath,
            aiProcess_Triangulate          |
                    aiProcess_GenSmoothNormals     |
                    aiProcess_FlipUVs              |
                    aiProcess_JoinIdenticalVertices|
                    aiProcess_ValidateDataStructure);

    if (!scene || scene->mFlags & AI_SCENE_FLAGS_INCOMPLETE || !scene->mRootNode) {
        LOGE("Assimp import failed: %s", importer.GetErrorString());
        env->ReleaseStringUTFChars(inputPathJ,  inputPath);
        env->ReleaseStringUTFChars(outputPathJ, outputPath);
        return JNI_FALSE;
    }

    // ── Assign a default PBR material for STL (no material data) ─────────────
    // OBJ files usually have materials already; STL never does.
    std::string ext(inputPath);
    bool isStl = ext.size() >= 4 &&
            (ext.substr(ext.size()-4) == ".stl" || ext.substr(ext.size()-4) == ".STL");

    if (isStl && scene->mNumMaterials > 0) {
        for (unsigned int i = 0; i < scene->mNumMaterials; i++) {
            aiMaterial* mat = scene->mMaterials[i];

            // Grey matte PBR defaults — renders correctly in SceneView
            aiColor4D baseColor(0.7f, 0.7f, 0.7f, 1.0f);
            mat->AddProperty(&baseColor, 1, AI_MATKEY_BASE_COLOR);

            float metallic  = 0.0f;
            float roughness = 0.6f;
            mat->AddProperty(&metallic,  1, AI_MATKEY_METALLIC_FACTOR);
            mat->AddProperty(&roughness, 1, AI_MATKEY_ROUGHNESS_FACTOR);
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