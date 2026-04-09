plugins {
    kotlin("multiplatform") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "main"
                baseName = "idk"
            }
        }
    }
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "main"
                baseName = "idk"
                linkerOpts(
                    "-L/usr/lib/aarch64-linux-gnu",
                    "-L/usr/lib/gcc-cross/aarch64-linux-gnu/13",
                    "--allow-shlib-undefined",
                    "-lssl", "-lcrypto",
                    "-lssh",
                    "-lbrotlidec",
                    "-lgssapi_krb5",
                    "-lidn2",
                    "-lldap", "-llber",
                    "-lnghttp2",
                    "-lpsl",
                    "-lrtmp",
                    "-lzstd",
                    "-lz",
                    "/usr/lib/gcc-cross/aarch64-linux-gnu/13/libgcc.a"
                )
            }
        }
    }


    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
            }
        }
        val unixArm64Main by creating {
            dependsOn(commonMain)
        }

        val linuxArm64Main by getting {
            dependsOn(unixArm64Main)
            dependencies {
                implementation("io.ktor:ktor-client-curl-linuxarm64:3.0.0")
            }
        }

        val macosArm64Main by getting {
            dependsOn(unixArm64Main)
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.0.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:3.0.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}
