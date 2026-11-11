package com.nathanhanapps.wayd.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D-052: the published SHA-256 is read from the source's own listing; shapes copied from the live APIs (2026-11-11). */
class PublishedShaTest {
    private val sha = ModelCatalog.E2B.sha256

    @Test fun huggingFaceTreeListing() {
        val json = """[{"type":"file","path":"README.md","size":10},
            {"type":"file","path":"gemma-4-E2B-it-gpu.litertlm","lfs":{"oid":"${"a".repeat(64)}","size":1}},
            {"type":"file","path":"gemma-4-E2B-it.litertlm","size":2588147712,"lfs":{"oid":"$sha","size":2588147712}}]"""
        assertEquals(sha, PublishedSha.huggingFace(json, "gemma-4-E2B-it.litertlm"))
        assertNull(PublishedSha.huggingFace(json, "missing.litertlm"))
        assertNull("a file without LFS metadata has no hash", PublishedSha.huggingFace(json, "README.md"))
    }

    @Test fun modelScopeFileListing() {
        val json = """{"Code":200,"Data":{"Files":[{"Name":"gemma-4-E2B-it-web.litertlm","Sha256":"${"b".repeat(64)}"},
            {"Name":"gemma-4-E2B-it.litertlm","Sha256":"$sha","Size":2588147712}]}}"""
        assertEquals(sha, PublishedSha.modelScope(json, "gemma-4-E2B-it.litertlm"))
        assertNull(PublishedSha.modelScope("""{"Code":404}""", "gemma-4-E2B-it.litertlm"))
    }

    @Test fun malformedHashesAreIgnored() {
        assertNull(PublishedSha.huggingFace("""[{"path":"x.litertlm","lfs":{"oid":"not-a-hash"}}]""", "x.litertlm"))
        assertNull(PublishedSha.modelScope("""{"Data":{"Files":[{"Name":"x.litertlm","Sha256":"${"z".repeat(64)}"}]}}""", "x.litertlm"))
    }

    @Test fun catalogKnowsItsRepositories() {
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", ModelCatalog.E2B.repo)
        assertEquals("litert-community/gemma-4-E4B-it-litert-lm", ModelCatalog.E4B.repo)
    }
}
