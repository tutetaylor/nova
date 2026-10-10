plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mateo.nova"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mateo.nova"
        minSdk = 26
        targetSdk = 35
        // GitHub pasa el número de compilación: cada versión nueva es mayor y Android la toma como actualización
        versionCode = (project.findProperty("numeroVersion") as String?)?.toIntOrNull() ?: 1
        versionName = "1.${versionCode}"
    }

    // Firma fija: todas las versiones salen con la misma llave, así se actualizan sin desinstalar
    signingConfigs {
        getByName("debug") {
            storeFile = file("nova.keystore")
            storePassword = "nova-firma"
            keyAlias = "nova"
            keyPassword = "nova-firma"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Cámara: "Nova, ¿qué es esto?"
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // Automatizaciones por lugar (llegar / salir)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Palabra de activación offline, sin bip y sin pausar el audio
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
