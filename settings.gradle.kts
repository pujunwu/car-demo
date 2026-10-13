pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "CarApp"

// 1. 经典 Socket H.264 方案模块 (完全独立)
include(":protocol")
include(":car")
include(":phone")

// 2. WebRTC 方案模块 (所有 WebRTC 相关的修改均限定在 car-webrtc 与 phone-webrtc 中)
include(":webrtc-core")
include(":car-webrtc")
include(":phone-webrtc")

// 3. 融合方案模块 (自定义采集与硬编码 + WebRTC传输 / WebRTC网络接收 + 自定义解码与渲染)
include(":car-new")
include(":phone-new")
