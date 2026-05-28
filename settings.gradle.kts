pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()

        // 可选：国内镜像
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()

        // 可选：国内镜像
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")

        // 高德 Maven 仓库，必须有
        maven {
            url = uri("http://maven.amap.com/repository/public")
            isAllowInsecureProtocol = true
        }

    }
}

rootProject.name = "GuideRunningFortheBlind"
include(":app")
