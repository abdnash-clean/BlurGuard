plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nash.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Macrobenchmarks will depend on the app and core modules
    implementation(libs.androidx.core.ktx)
}
