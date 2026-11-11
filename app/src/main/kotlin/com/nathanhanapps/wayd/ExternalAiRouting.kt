package com.nathanhanapps.wayd

import android.system.Os
import android.system.OsConstants
import com.nathanhanapps.wayd.ai.ExternalConnector
import com.nathanhanapps.wayd.ai.ExternalStream
import com.nathanhanapps.wayd.capture.RoutingBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * External AI calls follow Clash routing when it applies to WAYD itself (D-050). `ai/` only knows [ExternalConnector]; in the
 * `core` variant the bridge never routes, so every call uses the normal network.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExternalAiRouting {
    @Provides
    fun connector(routing: RoutingBridge): ExternalConnector = ExternalConnector { host, port ->
        routing.connect(host, port)?.let { routed ->
            val fd = routed.fd.fileDescriptor
            ExternalStream(FileInputStream(fd), FileOutputStream(fd), routed.route) {
                // shutdown wakes a read blocked on another thread; close alone may not.
                runCatching { Os.shutdown(fd, OsConstants.SHUT_RDWR) }
                runCatching { routed.fd.close() }
            }
        }
    }
}
