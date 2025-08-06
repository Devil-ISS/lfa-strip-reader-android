plugins {
    id 'com.android.application'
    id 'kotlin-android'
}

android {
    compileSdkVersion 33

    defaultConfig {
        applicationId "com.example.myapp"
        minSdkVersion 21
        targetSdkVersion 33
        versionCode 1
        versionName "1.0"
    }
    ...
}

dependencies {
    implementation "androidx.core:core-ktx:1.9.0"
    implementation "androidx.appcompat:appcompat:1.6.1"
    implementation "com.google.android.material:material:1.8.0"
    implementation "androidx.constraintlayout:constraintlayout:2.1.4"

    // CameraX
    def cameraX_version = "1.1.0"
    implementation "androidx.camera:camera-core:$cameraX_version"
    implementation "androidx.camera:camera-camera2:$cameraX_version"
    implementation "androidx.camera:camera-lifecycle:$cameraX_version"
    implementation "androidx.camera:camera-view:1.0.0-alpha32"

    // OpenCV
    implementation project(path: ":openCVLibrary")
    // implementation "org.opencv:opencv-android:4.5.5"
}
