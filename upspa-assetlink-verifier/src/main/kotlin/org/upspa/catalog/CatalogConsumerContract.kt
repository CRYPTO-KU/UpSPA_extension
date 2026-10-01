package org.upspa.catalog

import org.upspa.assetlinks.model.EnrolledOrigin

/**
 * Consumer Contract Interface (Architectural Proposal).
 *
 * This file defines the formal contract and invariants that downstream host environments
 * (e.g. Android Credential Manager, Desktop daemon, or Rust/FFI core) MUST adhere to
 * when consuming catalog documents.
 *
 * Contains NO implementation logic or stubs that report success.
 * Full integration into Android/FFI is deferred.
 *
 * ### Contract Invariants:
 * 1. **Validator Gatekeeping:**
 *    Consumers must obtain [CatalogDocument] instances exclusively through [CatalogValidator.validate].
 *    Unvalidated or raw JSON deserializations must never be consumed.
 *
 * 2. **Candidate Selection (Exact Origin Equality):**
 *    Candidate selection for credential release must use exact [EnrolledOrigin] equality
 *    (`origin == verifiedDestination`). Suffix, substring, registrable domain (eTLD+1),
 *    or package-only matching is strictly forbidden.
 *
 * 3. **Dual-Condition Release Eligibility:**
 *    Release of credentials requires BOTH:
 *    a. `entry.aliasEvidence.isEligibleForCredentialRelease == true` (i.e. status is VERIFIED).
 *    b. A fresh `VerificationResult.Verified` produced at request time by the Digital Asset Links
 *       verifier confirming that the verified origin, package name, and certificate digest
 *       exactly match [AliasEvidenceReference].
 *
 * 4. **No Silent Allow:**
 *    Evidence that is missing, stale, unavailable, or rejected by the live verifier must result
 *    in an unverified outcome. Insecure schemes (`http://`), manual aliases (`MANUAL`),
 *    `PENDING`, and `UNVERIFIED` entries must never release credentials.
 *
 * 5. **Diagnostics Hygiene:**
 *    Consumers must never log catalog fields (account references, policies, or origins).
 *    Internal diagnostic logs must use opaque local IDs.
 *
 * 6. **Dependency Injection:**
 *    Host dependencies (system clock, secure storage, platform package manager) must be injected
 *    via explicit interfaces to ensure hermetic testability with test doubles.
 */
interface CatalogConsumerContract {

    /**
     * Resolves an enrolled entry for a verified request destination.
     *
     * @param destinationOrigin The exact verified target origin.
     * @param accountReference The account reference to look up.
     * @return The matched [CatalogEntry] if found and verified, or null.
     */
    fun findEligibleEntry(
        destinationOrigin: EnrolledOrigin,
        accountReference: AccountReference
    ): CatalogEntry?
}
