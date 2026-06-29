plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nash.blurguard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nash.blurguard"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Features
    implementation(project(":feature:camera"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:settings"))

    // Core
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:camera"))
    implementation(project(":core:blurring"))
    implementation(project(":core:ml"))
    implementation(project(":core:tracking"))
    implementation(project(":core:recognition"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
