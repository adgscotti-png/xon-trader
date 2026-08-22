// I plugin si applicano nei singoli moduli; qui solo il pin delle versioni (catalog).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
