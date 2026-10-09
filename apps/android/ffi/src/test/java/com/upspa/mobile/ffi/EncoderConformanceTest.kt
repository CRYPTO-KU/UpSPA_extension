package com.upspa.mobile.ffi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.upspa_mobile_ffi.MobileException
import uniffi.upspa_mobile_ffi.NormalizedPasswordPolicy
import uniffi.upspa_mobile_ffi.SecretBytes
import uniffi.upspa_mobile_ffi.encodePassword
import java.io.File
import java.security.MessageDigest

/**
 * Kotlin-side conformance for the canonical Rust password encoder.
 *
 * Every vector goes through the generated Kotlin bindings into `upspa-core`'s encoder; nothing
 * about the encoding is reimplemented here. The shared corpus is only ever read. The one test
 * that needs a corrupted corpus works on a temporary copy.
 *
 * Output rule: assertion messages and logs contain vector IDs only. Secrets, expected passwords
 * and produced passwords are compared as byte arrays and wiped; they are never printed, which is
 * why this file never uses assertEquals on password values.
 *
 * Run: ./gradlew :ffi:testDebugUnitTest --tests 'com.upspa.mobile.ffi.EncoderConformanceTest'
 */
class EncoderConformanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Vector(
        val id: String,
        val secret: ByteArray,
        val policy: NormalizedPasswordPolicy,
        val accountId: String,
        val counter: UInt,
        val expected: ByteArray,
        val reject: Boolean,
    )

    private class Outcome {
        val accepted = mutableListOf<String>()
        val acceptedMismatches = mutableListOf<String>()
        val rejectsConfirmed = mutableListOf<String>()
        val rejectsUnexpectedlyMatching = mutableListOf<String>()
        val encoderErrors = mutableListOf<String>()
    }

    private fun corpusFile(): File {
        val path = System.getProperty("upspa.vectorCorpus")
            ?: error("upspa.vectorCorpus is not set; run through Gradle (see ffi/build.gradle.kts)")
        return File(path).also { check(it.isFile) { "corpus not found at $path" } }
    }

    private fun policyOf(json: JSONObject): NormalizedPasswordPolicy {
        val forbidden = json.getJSONArray("forbiddenSubstrings")
        return NormalizedPasswordPolicy(
            minLen = json.getInt("minLen").toUInt(),
            maxLen = json.getInt("maxLen").toUInt(),
            requireUpper = json.getBoolean("requireUpper"),
            requireLower = json.getBoolean("requireLower"),
            requireDigit = json.getBoolean("requireDigit"),
            requireSymbol = json.getBoolean("requireSymbol"),
            allowedSymbols = json.getString("allowedSymbols"),
            forbidWhitespace = json.getBoolean("forbidWhitespace"),
            forbiddenSubstrings = List(forbidden.length()) { forbidden.getString(it) },
        )
    }

    private fun load(file: File): List<Vector> {
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return List(array.length()) { i ->
            val v = array.getJSONObject(i)
            Vector(
                id = v.getString("id"),
                secret = v.getString("secretB64").toByteArray(Charsets.UTF_8),
                policy = policyOf(v.getJSONObject("policy")),
                accountId = v.getString("accountId"),
                counter = v.getLong("counter").toUInt(),
                expected = v.getString("expected").toByteArray(Charsets.UTF_8),
                reject = v.getBoolean("reject"),
            )
        }
    }

    /** Runs every vector through the Rust encoder and classifies it by ID only. */
    private fun check(vectors: List<Vector>): Outcome {
        val outcome = Outcome()
        for (v in vectors) {
            // The bindings copy the secret across the boundary; wipe our copy afterwards.
            val secretCopy = v.secret.copyOf()
            val produced = try {
                encodePassword(SecretBytes(secretCopy), v.policy, v.accountId, v.counter)
            } catch (e: MobileException) {
                outcome.encoderErrors += v.id
                continue
            } finally {
                secretCopy.fill(0)
            }
            val matches = MessageDigest.isEqual(produced.bytes, v.expected)
            produced.bytes.fill(0)

            when {
                !v.reject && matches -> outcome.accepted += v.id
                !v.reject -> outcome.acceptedMismatches += v.id
                matches -> outcome.rejectsUnexpectedlyMatching += v.id
                else -> outcome.rejectsConfirmed += v.id
            }
        }
        return outcome
    }

    @Test
    fun `every accepted vector matches the canonical Rust encoder`() {
        val vectors = load(corpusFile())
        val outcome = check(vectors)

        assertEquals("corpus size", 22, vectors.size)
        assertTrue("encoder errors for IDs: ${outcome.encoderErrors}", outcome.encoderErrors.isEmpty())
        assertTrue("mismatch for IDs: ${outcome.acceptedMismatches}", outcome.acceptedMismatches.isEmpty())
        assertEquals("accepted vector count", vectors.count { !it.reject }, outcome.accepted.size)
        println("conformance: ${outcome.accepted.size} accepted vectors match: ${outcome.accepted.joinToString()}")
    }

    @Test
    fun `the intentionally mismatched vector is explicitly rejected`() {
        val outcome = check(load(corpusFile()))

        assertTrue(
            "reject vector unexpectedly matched for IDs: ${outcome.rejectsUnexpectedlyMatching}",
            outcome.rejectsUnexpectedlyMatching.isEmpty(),
        )
        assertEquals("exactly one reject vector", 1, outcome.rejectsConfirmed.size)
        println("conformance: reject vector(s) confirmed as mismatching: ${outcome.rejectsConfirmed.joinToString()}")
    }

    @Test
    fun `corrupting an accepted vector in a temporary copy makes the check fail`() {
        val original = corpusFile()
        val originalDigest = MessageDigest.getInstance("SHA-256").digest(original.readBytes())

        // Corrupt the first accepted vector's expected value in a temporary copy only.
        val array = JSONArray(original.readText(Charsets.UTF_8))
        var corruptedId: String? = null
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            if (!v.getBoolean("reject")) {
                val expected = v.getString("expected")
                val last = expected.last()
                val replacement = if (last == 'A') 'B' else 'A'
                v.put("expected", expected.dropLast(1) + replacement)
                corruptedId = v.getString("id")
                break
            }
        }
        checkNotNull(corruptedId) { "corpus has no accepted vector" }
        val copy = tmp.newFile("vectors-corrupted.json").apply { writeText(array.toString(2)) }

        val outcome = check(load(copy))

        assertEquals("only the corrupted ID fails", listOf(corruptedId), outcome.acceptedMismatches)
        println("conformance: corrupted copy failed as expected for ID: $corruptedId")

        // The shared corpus was never modified.
        val afterDigest = MessageDigest.getInstance("SHA-256").digest(original.readBytes())
        assertTrue("shared corpus was modified", MessageDigest.isEqual(originalDigest, afterDigest))
    }
}
