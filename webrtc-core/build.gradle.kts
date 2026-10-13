plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.webrtc.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol"))
    api(libs.webrtc)
    implementation(libs.androidx.core.ktx)
    implementation(libs.gson)
}
