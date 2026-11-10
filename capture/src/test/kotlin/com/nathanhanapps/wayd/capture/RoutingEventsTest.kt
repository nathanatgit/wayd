package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class RoutingEventsTest {
    private fun event(kind: Int, extra: Int = 0) = ByteBuffer.allocate(14 + extra)
        .putInt(14 + extra).put(8).putLong(31).put(kind.toByte()).array()

    @Test fun routeWireRequiresExactLengthAndKnownKind() {
        for (kind in 1..4) {
            val bytes = event(kind)
            val decoded = NativeEvents.decode(bytes, bytes.size).single() as NativeEvent.Route
            assertEquals(31L, decoded.flowId)
            assertEquals(kind, decoded.kind)
            assertTrue(NativeEvents.decode(bytes, bytes.size - 1).isEmpty())
            assertTrue(NativeEvents.decode(event(kind, 1), 15).isEmpty())
        }
        assertTrue(NativeEvents.decode(event(0), 14).isEmpty())
        assertTrue(NativeEvents.decode(event(5), 14).isEmpty())
    }

    @Test fun outcomesStaySeparateAndUnknownKindsDoNotCount() {
        val actual = listOf(1, 2, 3, 4, 2, 99).fold(RouteStats()) { stats, kind -> stats.add(kind) }
        assertEquals(RouteStats(direct = 1, proxied = 2, blocked = 1, errors = 1), actual)
    }
}
