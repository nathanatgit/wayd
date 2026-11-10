// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"bytes"
	"context"
	"io"
	"net"
	"time"

	"github.com/metacubex/mihomo/adapter/outbound"
	C "github.com/metacubex/mihomo/constant"
	MLog "github.com/metacubex/mihomo/log"
	"github.com/sirupsen/logrus"
)

func init() {
	// No upstream diagnostics enter Android stdout/logcat, even on transport errors.
	MLog.SetLevel(MLog.SILENT)
	logrus.SetOutput(io.Discard)
}

const EngineVersion = "mihomo/v1.19.33;wayd-api/1"

// Engine owns an immutable profile and only the explicitly supported transports.
// Callers must enforce WAYD blocks before calling any dial method.
type Engine struct {
	profile   *Profile
	adapters  map[string]C.ProxyAdapter
	extension *extensionEngine
}

func New(data []byte) (*Engine, error) {
	if bytes.HasPrefix(data, []byte(extensionPrefix)) {
		return newExtension(data)
	}
	p, err := Parse(data)
	if err != nil {
		return nil, err
	}
	e := &Engine{profile: p, adapters: map[string]C.ProxyAdapter{}}
	for name, s := range p.proxies {
		var a C.ProxyAdapter
		switch s.Type {
		case "socks5":
			a, err = outbound.NewSocks5(outbound.Socks5Option{Name: name, Server: s.Server, Port: s.Port, UserName: s.Username, Password: s.Password, TLS: s.TLS, UDP: s.UDP})
		case "http":
			a, err = outbound.NewHttp(outbound.HttpOption{Name: name, Server: s.Server, Port: s.Port, UserName: s.Username, Password: s.Password, TLS: s.TLS})
		}
		if err != nil {
			return nil, ErrProfile
		}
		e.adapters[name] = a
	}
	return e, nil
}

func (e *Engine) NeedsHost() bool {
	if e.extension != nil {
		return true
	}
	return e.profile.NeedsHost()
}
func (e *Engine) NeedsOwner() bool {
	if e.extension != nil {
		return true
	}
	return e.profile.NeedsOwner()
}
func (e *Engine) Select(m Metadata) (Route, error) {
	if e.extension != nil {
		return e.extension.selectRoute(m)
	}
	return e.profile.Select(m)
}
func (e *Engine) Close() {
	if e.extension != nil {
		e.extension.close()
	} else {
		for _, a := range e.adapters {
			_ = a.Close()
		}
	}
}

func liveMetadata(m Metadata) *C.Metadata {
	network := C.TCP
	if m.UDP {
		network = C.UDP
	}
	// Preserve the VPN's resolved destination. A visible host is used only for
	// WAYD-side selection; transport must not resolve it a second time.
	return &C.Metadata{NetWork: network, DstIP: m.Destination.Addr(), DstPort: m.Destination.Port()}
}

// DialTCP reports the selected route even on failure, never retrying Direct.
// Error strings are fixed so proxy credentials and remote diagnostics cannot leak.
func (e *Engine) DialTCP(ctx context.Context, m Metadata) (net.Conn, Route, error) {
	r, err := e.Select(m)
	if err != nil {
		return nil, r, err
	}
	if m.UDP {
		return nil, r, ErrMetadata
	}
	if r.Kind == "Blocked" {
		return nil, r, ErrRejected
	}
	ctx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	var c net.Conn
	if r.Kind == "Direct" {
		var d net.Dialer
		c, err = d.DialContext(ctx, "tcp", m.Destination.String())
	} else {
		c, err = e.adapters[r.Name].DialContext(ctx, liveMetadata(m))
	}
	if err != nil {
		return nil, r, ErrTransport
	}
	return c, r, nil
}

// OpenUDP returns a connected-flow packet transport. Callers write only to the
// approved destination and must close it on policy cancellation/VPN shutdown.
func (e *Engine) OpenUDP(ctx context.Context, m Metadata) (net.PacketConn, Route, error) {
	r, err := e.Select(m)
	if err != nil {
		return nil, r, err
	}
	if !m.UDP {
		return nil, r, ErrMetadata
	}
	if r.Kind == "Blocked" {
		return nil, r, ErrRejected
	}
	ctx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	if r.Kind == "Direct" {
		addr := "0.0.0.0:0"
		if m.Destination.Addr().Is6() {
			addr = "[::]:0"
		}
		var lc net.ListenConfig
		c, err := lc.ListenPacket(ctx, "udp", addr)
		if err != nil {
			return nil, r, ErrTransport
		}
		return c, r, nil
	}
	a := e.adapters[r.Name]
	if !a.SupportUDP() {
		return nil, r, ErrTransport
	}
	c, err := a.ListenPacketContext(ctx, liveMetadata(m))
	if err != nil {
		return nil, r, ErrTransport
	}
	return c, r, nil
}
