// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"context"
	"errors"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/miekg/dns"
	"net"
	"net/netip"
)

// Proxy hostnames use the underlying Android network resolver. WAYD itself is
// excluded from its VPN; there is no recursive VPN DNS lookup or public resolver
// hardcoded here. Profile DNS overrides are disclosed separately in import UI.
type systemBootstrap struct{}

func (systemBootstrap) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	return net.DefaultResolver.LookupNetIP(ctx, "ip", host)
}
func (systemBootstrap) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	return net.DefaultResolver.LookupNetIP(ctx, "ip4", host)
}
func (systemBootstrap) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	return net.DefaultResolver.LookupNetIP(ctx, "ip6", host)
}
func (systemBootstrap) ResolveECH(context.Context, string) ([]byte, error) {
	return nil, errors.New("ech_lookup_unavailable")
}
func (systemBootstrap) ExchangeContext(context.Context, *dns.Msg) (*dns.Msg, error) {
	return nil, errors.New("raw_dns_unavailable")
}
func (systemBootstrap) Invalid() bool    { return false }
func (systemBootstrap) ClearCache()      {}
func (systemBootstrap) ResetConnection() {}
func init() {
	if resolver.SystemResolver == nil {
		resolver.SystemResolver = systemBootstrap{}
	}
}
