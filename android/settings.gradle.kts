import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        val pamPlugins = Properties()
        val pamPluginsFile = rootDir.resolve("pam-plugins.properties")
        if (pamPluginsFile.isFile) {
            pamPluginsFile.inputStream().use(pamPlugins::load)
        }
        val repositoryCount = pamPlugins.getProperty("repository.count", "0").toInt()
        repeat(repositoryCount) { index ->
            maven {
                name = "pamPluginRepository$index"
                url = uri(pamPlugins.getProperty("repository.$index"))
            }
        }
    }
}

rootProject.name = "PamNativeAndroid"
include(":app")
include(":macrobenchmark")
include(":plugin-api")

val pamPlugins = Properties()
val pamPluginsFile = rootDir.resolve("pam-plugins.properties")
if (pamPluginsFile.isFile) {
    pamPluginsFile.inputStream().use(pamPlugins::load)
}
val pluginCount = pamPlugins.getProperty("plugin.count", "0").toInt()
repeat(pluginCount) { index ->
    val module = pamPlugins.getProperty("plugin.$index.module")
        ?: error("pam-plugins.properties is missing plugin.$index.module")
    val directory = pamPlugins.getProperty("plugin.$index.dir")
        ?: error("pam-plugins.properties is missing plugin.$index.dir")
    include(module)
    project(module).projectDir = file(directory)
}
