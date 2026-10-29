/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // NOTE(PawAM): `alias(libs.plugins.lineageos.generatebp)` is intentionally removed.
    // Upstream uses it only to regenerate app/Android.bp for the AOSP platform build.
    // PawAM is built standalone with Android Studio / Gradle, where that plugin cannot resolve.
}
