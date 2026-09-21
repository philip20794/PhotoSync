import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "de.photosync"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.photosync"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional deployment configuration; never include a service-account key.
        resValue("string", "google_app_id", providers.gradleProperty("firebaseAppId").getOrElse(""))
        resValue("string", "gcm_defaultSenderId", providers.gradleProperty("firebaseSenderId").getOrElse(""))
        resValue("string", "google_api_key", providers.gradleProperty("firebaseApiKey").getOrElse(""))
        resValue("string", "project_id", providers.gradleProperty("firebaseProjectId").getOrElse(""))
    }

    val signingPropertiesPath = providers.gradleProperty("photosyncSigningProperties")
        .orElse(providers.environmentVariable("PHOTOSYNC_SIGNING_PROPERTIES"))
        .orElse("${System.getProperty("user.home")}/.keys/photosync/release-signing.properties")
    val signingPropertiesFile = file(signingPropertiesPath.get())
    val signingProperties = Properties().apply {
        if (signingPropertiesFile.isFile) {
            FileInputStream(signingPropertiesFile).use { load(it) }
        }
    }
    val releaseSigning = signingConfigs.create("photosyncRelease") {
        if (signingPropertiesFile.isFile) {
            storeFile = file(requireNotNull(signingProperties.getProperty("storeFile")))
            storePassword = requireNotNull(signingProperties.getProperty("storePassword"))
            keyAlias = requireNotNull(signingProperties.getProperty("keyAlias"))
            keyPassword = requireNotNull(signingProperties.getProperty("keyPassword"))
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "IS_PRODUCTION", "false")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "IS_PRODUCTION", "true")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://philsync.duckdns.org/\"")
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation("androidx.room:room-paging:2.7.1")
    implementation(libs.androidx.work.runtime)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation(libs.mockwebserver)
}
