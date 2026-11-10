package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingResourceUrlsTest {
    private val raw = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/country.mmdb"
    @Test fun mirrorRequiresExplicitChoice() {
        assertEquals(raw, RoutingResourceUrls.forDownload(raw, false))
        assertEquals("https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/country.mmdb", RoutingResourceUrls.forDownload(raw, true))
    }
    @Test fun tokensAndOtherOriginsNeverGoToMirror() {
        for (address in listOf("$raw?token=secret", "$raw#secret", raw.replace("https://", "https://user:secret@"),
            raw.replace("https:", "http:"), raw.replace("raw.githubusercontent.com", "example.com"),
            raw.replace("/release/", "/%2e%2e/"))) {
            assertEquals(address, RoutingResourceUrls.forDownload(address, true))
        }
    }
}
