plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nash.core.recognition"
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
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    
    implementation(libs.androidx.core.ktx)
}
