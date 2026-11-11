// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"context"
	"net"
	"strconv"
	"strings"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

// DialName opens a TCP stream to host:port for WAYD's own external AI call (D-050). Unlike DialTCP it starts from a name, not
// a VPN-resolved address: the selected node resolves the name, so a poisoned local DNS answer cannot redirect the call.
// IP and GEOIP rules cannot match without an address and are skipped, like Mihomo's no-resolve rules. The caller runs TLS
// over the returned stream, so the engine only ever carries ciphertext. A failed proxy dial never retries Direct.
func (e *Engine) DialName(ctx context.Context, host string, port uint16, owner string) (net.Conn, Route, error) {
	if e.extension == nil || !domain(host) || port == 0 || (owner != "" && !validOwner(owner)) {
		return nil, Route{}, ErrMetadata
	}
	meta := &C.Metadata{NetWork: C.TCP, Host: host, DstPort: port, Type: C.TUN}
	_, meta.Process, _ = strings.Cut(owner, ":")
	r, err := e.extension.resolve(meta)
	if err != nil {
		return nil, r, err
	}
	if r.Kind == "Blocked" {
		return nil, r, ErrRejected
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	var c net.Conn
	if r.Kind == "Direct" {
		var d net.Dialer
		c, err = d.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(int(port))))
	} else {
		a := e.adapters[r.Name]
		if a == nil {
			return nil, r, ErrProfile
		}
		c, err = a.DialContext(ctx, meta)
	}
	if err != nil {
		return nil, r, ErrTransport
	}
	return c, r, nil
}
