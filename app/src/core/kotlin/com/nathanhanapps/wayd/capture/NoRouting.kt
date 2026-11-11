package com.nathanhanapps.wayd.capture

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * `core` variant: no routing code, no libwayd_route.so. Routing is always off. A profile a `clash` install left in
 * no_backup/routing/ is never read, changed or deleted here, so reinstalling `clash` restores it (D-049).
 */
object NoRouting : RoutingBridge {
    override suspend fun prepare() = RoutingSnapshot()
    override fun affected(pkg: String?) = false
    override suspend fun refresh() = Unit
    override fun country(ip: String): String? = null
    override suspend fun connect(host: String, port: Int): RoutedStream? = null
}

@Module
@InstallIn(SingletonComponent::class)
object NoRoutingModule {
    @Provides fun bridge(): RoutingBridge = NoRouting
}
