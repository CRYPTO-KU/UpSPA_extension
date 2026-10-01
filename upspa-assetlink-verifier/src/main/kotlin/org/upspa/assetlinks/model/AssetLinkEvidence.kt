package org.upspa.assetlinks.model

/**
 * Encapsulates Digital Asset Links statement evidence cryptographically or architecturally
 * bound to its authoritative source origin (§1.2, §1.4).
 *
 * Mitigates cross-origin evidence replay attacks where valid statements published by
 * Origin A are submitted to improperly authorize an operation under Origin B.
 *
 * @property sourceOrigin The authoritative web origin that published or issued these statements.
 * @property statements The parsed Digital Asset Links statements retrieved from [sourceOrigin].
 */
data class AssetLinkEvidence(
    val sourceOrigin: Origin,
    val statements: List<AssetLinkStatement>
)
