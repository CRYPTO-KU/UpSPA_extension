package org.upspa.catalog

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

class CatalogFixtureTest {

    data class ManifestEntry(
        val file: String,
        val expected: String,
        val code: String? = null,
        val entryIndex: Int? = null,
        val path: String? = null,
        val parentPath: String? = null,
        val found: Long? = null,
        val firstIndex: Int? = null,
        val secondIndex: Int? = null,
        val comment: String? = null
    )

    @Test
    fun `manifest strictly matches files on disk bi-directionally`() {
        val baseDir = getFixtureBaseDir()
        val manifestFile = File(baseDir, "manifest.json")
        assertTrue(manifestFile.exists(), "manifest.json must exist")

        val manifestEntries = loadManifestEntries(manifestFile)
        val manifestFiles = manifestEntries.map { it.file.replace('\\', '/') }.toSet()

        val diskFiles = mutableSetOf<String>()
        val validDir = File(baseDir, "valid")
        if (validDir.exists()) {
            validDir.listFiles()?.filter { it.extension == "json" }?.forEach {
                diskFiles.add("valid/${it.name}")
            }
        }
        val invalidDir = File(baseDir, "invalid")
        if (invalidDir.exists()) {
            invalidDir.listFiles()?.filter { it.extension == "json" }?.forEach {
                diskFiles.add("invalid/${it.name}")
            }
        }

        // Bi-directional equality check
        val missingFromManifest = diskFiles - manifestFiles
        val missingFromDisk = manifestFiles - diskFiles

        assertTrue(
            missingFromManifest.isEmpty(),
            "Files present on disk but missing from manifest: $missingFromManifest"
        )
        assertTrue(
            missingFromDisk.isEmpty(),
            "Files in manifest but missing from disk: $missingFromDisk"
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureProvider")
    fun `validate fixture expectations`(entry: ManifestEntry) {
        val baseDir = getFixtureBaseDir()
        val fixtureFile = File(baseDir, entry.file)
        assertTrue(fixtureFile.exists(), "Fixture file ${entry.file} must exist")
        val rawJson = fixtureFile.readText(Charsets.UTF_8)

        val validator = StrictCatalogValidator()
        val result = validator.validate(rawJson)

        if (entry.expected == "ACCEPTED") {
            assertTrue(
                result is CatalogValidationResult.Accepted,
                "Expected ACCEPTED for ${entry.file}, got $result"
            )
        } else {
            assertTrue(
                result is CatalogValidationResult.Rejected,
                "Expected Rejected(${entry.expected}) for ${entry.file}, got $result"
            )
            val rejection = (result as CatalogValidationResult.Rejected).rejection
            val rejectionClassName = rejection::class.simpleName
            assertEquals(
                entry.expected,
                rejectionClassName,
                "Rejection class mismatch for ${entry.file}"
            )

            // Assert specific rejection fields when specified in manifest
            when (rejection) {
                is CatalogRejection.InvalidAccountReference -> {
                    if (entry.code != null) assertEquals(entry.code, rejection.code.name)
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.MalformedPolicy -> {
                    if (entry.code != null) assertEquals(entry.code, rejection.code.name)
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.MalformedAliasEvidence -> {
                    if (entry.code != null) assertEquals(entry.code, rejection.code.name)
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.WrongType -> {
                    if (entry.path != null) assertEquals(entry.path, rejection.path)
                }
                is CatalogRejection.MissingField -> {
                    if (entry.path != null) assertEquals(entry.path, rejection.path)
                }
                is CatalogRejection.MissingEnrolledOrigin -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.UnsupportedSchemaVersion -> {
                    if (entry.found != null) assertEquals(entry.found, rejection.found)
                }
                is CatalogRejection.UnsupportedCompatibilityProfileVersion -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                    if (entry.found != null) assertEquals(entry.found, rejection.found)
                }
                is CatalogRejection.InvalidEnrolledOrigin -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.NonCanonicalEnrolledOrigin -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.PartialPolicy -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.InvalidEncoderCounter -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.AliasOriginMismatch -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.AliasRequiresHttps -> {
                    if (entry.entryIndex != null) assertEquals(entry.entryIndex, rejection.entryIndex)
                }
                is CatalogRejection.UnknownField -> {
                    if (entry.parentPath != null) assertEquals(entry.parentPath, rejection.parentPath)
                }
                is CatalogRejection.DuplicateJsonKey -> {
                    if (entry.path != null) assertEquals(entry.path, rejection.path)
                }
                is CatalogRejection.DuplicateEntry -> {
                    if (entry.firstIndex != null) assertEquals(entry.firstIndex, rejection.firstIndex)
                    if (entry.secondIndex != null) assertEquals(entry.secondIndex, rejection.secondIndex)
                }
                else -> {}
            }
        }
    }

    companion object {
        private fun getFixtureBaseDir(): File {
            val res = CatalogFixtureTest::class.java.getResource("/catalog/v1/manifest.json")
            if (res != null) {
                return File(res.toURI()).parentFile
            }
            // Fallback for file-system lookup
            return File("src/test/resources/catalog/v1")
        }

        private fun loadManifestEntries(manifestFile: File): List<ManifestEntry> {
            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
            val listType = Types.newParameterizedType(List::class.java, ManifestEntry::class.java)
            val adapter = moshi.adapter<List<ManifestEntry>>(listType)
            return adapter.fromJson(manifestFile.readText(Charsets.UTF_8)) ?: emptyList()
        }

        @JvmStatic
        fun fixtureProvider(): Stream<ManifestEntry> {
            val baseDir = getFixtureBaseDir()
            val manifestFile = File(baseDir, "manifest.json")
            return loadManifestEntries(manifestFile).stream()
        }
    }
}
