// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("idea")
}

configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
    module {
        excludeDirs.addAll(listOf(file(".idea"), file(".kotlin"), file(".artifacts"), file(path = "backup"), file(path = "wiki"), file(path = "bplaced_php")))
    }
}