import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noyclic.questphonexr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noyclic.questphonexr"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")

    val cameraXVersion = "1.4.2"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.65")

    testImplementation("junit:junit:4.13.2")
}

val handLandmarkerModel = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")
val downloadHandLandmarkerModel by tasks.registering {
    outputs.file(handLandmarkerModel)
    doLast {
        val destination = handLandmarkerModel.asFile
        if (!destination.exists() || destination.length() < 1_000_000L) {
            destination.parentFile.mkdirs()
            val temporaryFile = destination.resolveSibling("${destination.name}.part")
            try {
                URL("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task")
                    .openConnection()
                    .apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                    }
                    .getInputStream()
                    .use { input -> temporaryFile.outputStream().use { output -> input.copyTo(output) } }

                check(temporaryFile.length() >= 1_000_000L) {
                    "Downloaded Hand Landmarker model is unexpectedly small."
                }
                if (destination.exists()) destination.delete()
                check(temporaryFile.renameTo(destination)) {
                    "Could not move the downloaded model into the assets directory."
                }
            } finally {
                if (temporaryFile.exists()) temporaryFile.delete()
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadHandLandmarkerModel) }
