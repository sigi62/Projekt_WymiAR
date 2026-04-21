plugins {
    alias(libs.plugins.kotlin.android)
    id("com.android.dynamic-feature")
}
android {
    namespace = "com.example.pracazaliczeniowa.converter"
    compileSdk = 35
//    {
//        version = release(36)
//    }

    defaultConfig {
        minSdk = 25
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                // Use arguments += to add multiple arguments without overwriting
                arguments("-DANDROID_STL=c++_shared")
            }
        }
        // Correct Kotlin DSL syntax for ndk filters
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }


}

dependencies {
    implementation(project(":app"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")
}