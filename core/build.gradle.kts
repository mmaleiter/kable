plugins {
    // Android plugin must be before multiplatform plugin until https://youtrack.jetbrains.com/issue/KT-34038 is fixed.
    id("com.android.library")
    kotlin("multiplatform")
    id("kotlinx-atomicfu")
    id("org.jmailen.kotlinter")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

/* ```
 *   common
 *   |-- js
 *   |-- android
 *   '-- apple
 *       |-- ios
 *       '-- macos
 * ```
 */
kotlin {
    explicitApi()

    android {
        publishAllLibraryVariants()
    }
    js().browser()
    iosX64()
    macosArm64()
    iosArm32()
    iosArm64()
    iosSimulatorArm64()
    macosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":exceptions"))
                api(libs.kotlinx.coroutines.core)
                api(libs.uuid)
                implementation(libs.tuulbox.collections)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.tuulbox.logging)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }

        val androidMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.android)
                implementation(libs.atomicfu)
                implementation(libs.androidx.core)
                implementation(libs.androidx.startup)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val appleMain by creating {
            dependsOn(commonMain)
        }

        val appleTest by creating

        val macosX64Main by getting {
            dependsOn(appleMain)
        }

        val macosX64Test by getting {
            dependsOn(appleTest)
        }

        val macosArm64Main by getting {
            dependsOn(appleMain)
        }

        val macosArm64Test by getting {
            dependsOn(appleTest)
        }

        val iosX64Main by getting {
            dependsOn(appleMain)
        }

        val iosX64Test by getting {
            dependsOn(appleTest)
        }

        val iosArm32Main by getting {
            dependsOn(appleMain)
        }

        val iosArm32Test by getting {
            dependsOn(appleTest)
        }

        val iosArm64Main by getting {
            dependsOn(appleMain)
        }

        val iosArm64Test by getting {
            dependsOn(appleTest)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(appleMain)
        }
        val iosSimulatorArm64Test by getting {
            dependsOn(appleTest)
        }

        all {
            languageSettings.enableLanguageFeature("InlineClasses")
        }
    }
}

android {
    compileSdk = libs.versions.android.compile.get().toInt()
    defaultConfig.minSdk = libs.versions.android.min.get().toInt()

    namespace = "com.juul.kable"

    lint {
        abortOnError = true
        warningsAsErrors = true

        // Calls to many functions on `BluetoothDevice`, `BluetoothGatt`, etc require `BLUETOOTH_CONNECT` permission,
        // which has been specified in the `AndroidManifest.xml`; rather than needing to annotate a number of classes,
        // we disable the "missing permission" lint check. Caution must be taken during later Android version bumps to
        // make sure we aren't missing any newly introduced permission requirements.
        disable += "MissingPermission"
    }

    // Android Gradle plugin targets JVM 11 bytecode
    // https://developer.android.com/studio/releases/gradle-plugin#7-4-0
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
