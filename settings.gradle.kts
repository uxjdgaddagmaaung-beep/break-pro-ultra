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
    }
}

rootProject.name = "BreakProUltra"
include(":app")
// Planned modules (added phase by phase):
// :core, :media-engine, :timeline-engine, :effects-engine, :animation-engine,
// :photo-engine, :audio-engine, :vector-engine, :export-engine,
// :asset-manager, :ai-engine, :project-manager
