import kotlinx.cinterop.toKString
import platform.posix.getenv

val isDevelopmentEnvironment = getenv("IDK_DEVELOPMENT")?.toKString().isNullOrEmpty().not()

fun getBinaryPath(architectureName: String): String {
    check(architectureName in listOf("linuxX64","linuxArm64","macosArm64")) {
        "Unsupported architecture: $architectureName"
    }
    return if (isDevelopmentEnvironment) {
        "./build/bin/${architectureName}/debugExecutable/idk.kexe"
    } else {
        "./build/bin/${architectureName}/releaseExecutable/idk.kexe"
    }
}