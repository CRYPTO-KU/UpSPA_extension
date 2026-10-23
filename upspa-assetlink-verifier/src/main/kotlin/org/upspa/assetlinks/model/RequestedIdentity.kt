package org.upspa.assetlinks.model

/**
 * Encapsulates a verification request identifying the target web origin, Android application package,
 * and the specific requested permission relationship (§1.5).
 *
 * @property origin The canonical web origin asserting permission.
 * @property packageName The claimed Android application package name.
 * @property requiredRelation Optional required permission relation (e.g. `delegate_permission/common.handle_all_urls`).
 */
data class RequestedIdentity(
    val origin: Origin,
    val packageName: AndroidPackageName,
    val requiredRelation: String? = null
) {
    val canonicalWebOrigin: CanonicalWebOrigin get() = CanonicalWebOrigin.of(origin)

    companion object {
        @JvmStatic
        fun of(
            origin: Origin,
            packageName: AndroidPackageName,
            requiredRelation: String? = null
        ): RequestedIdentity = RequestedIdentity(origin, packageName, requiredRelation)

        @JvmStatic
        fun of(
            origin: Origin,
            packageName: String,
            requiredRelation: String? = null
        ): RequestedIdentity = RequestedIdentity(origin, AndroidPackageName.of(packageName), requiredRelation)

        @JvmStatic
        fun of(
            canonicalOrigin: CanonicalWebOrigin,
            packageName: AndroidPackageName,
            requiredRelation: String? = null
        ): RequestedIdentity = RequestedIdentity(canonicalOrigin.origin, packageName, requiredRelation)

        @JvmStatic
        fun of(
            canonicalOrigin: CanonicalWebOrigin,
            packageName: String,
            requiredRelation: String? = null
        ): RequestedIdentity = RequestedIdentity(canonicalOrigin.origin, AndroidPackageName.of(packageName), requiredRelation)
    }
}
