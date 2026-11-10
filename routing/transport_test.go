// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func fixture(t *testing.T, handler func(net.Conn)) net.Listener {
	t.Helper()
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, e := l.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); _ = c.SetDeadline(time.Now().Add(3 * time.Second)); handler(c) }()
		}
	}()
	return l
}

func TestMihomoHTTPConnect(t *testing.T) {
	var reached atomic.Int32
	proxy := fixture(t, func(c net.Conn) {
		r, e := http.ReadRequest(bufio.NewReader(c))
		if e != nil || r.Method != "CONNECT" || r.Host != "192.0.2.4:443" || r.Header.Get("Proxy-Authorization") == "" {
			return
		}
		reached.Add(1)
		_, _ = io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n")
		_, _ = io.Copy(c, c)
	})
	port := proxy.Addr().(*net.TCPAddr).Port
	p := fmt.Sprintf("proxies:\n - {name: test, type: http, server: 127.0.0.1, port: %d, username: u, password: secret}\nrules: ['MATCH,test']\n", port)
	e, err := New([]byte(p))
	if err != nil {
		t.Fatal(err)
	}
	c, r, err := e.DialTCP(context.Background(), Metadata{Destination: netip.MustParseAddrPort("192.0.2.4:443")})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = c.Write([]byte("ping"))
	b := make([]byte, 4)
	_, err = io.ReadFull(c, b)
	if err != nil || string(b) != "ping" || r.Kind != "Proxied" || reached.Load() != 1 {
		t.Fatalf("proxy fixture failed: %v", err)
	}
}

func TestProxyFailureNeverDialsDestination(t *testing.T) {
	var direct atomic.Int32
	dst := fixture(t, func(c net.Conn) { direct.Add(1) })
	// A live proxy endpoint explicitly refuses CONNECT; DIRECT is reachable.
	proxy := fixture(t, func(c net.Conn) {
		_, _ = http.ReadRequest(bufio.NewReader(c))
		_, _ = io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
	})
	p := fmt.Sprintf("proxies:\n - {name: fail, type: http, server: 127.0.0.1, port: %d}\nrules: ['MATCH,fail']", proxy.Addr().(*net.TCPAddr).Port)
	e, err := New([]byte(p))
	if err != nil {
		t.Fatal(err)
	}
	c, r, err := e.DialTCP(context.Background(), Metadata{Destination: netip.MustParseAddrPort(dst.Addr().String())})
	if c != nil || err != ErrTransport || r.Kind != "Proxied" || direct.Load() != 0 || strings.Contains(err.Error(), "127.0.0.1") {
		t.Fatal("failure did not remain closed")
	}
}

func TestRejectBeforeNetwork(t *testing.T) {
	var count atomic.Int32
	dst := fixture(t, func(c net.Conn) { count.Add(1) })
	e, err := New([]byte("rules: ['MATCH,REJECT']"))
	if err != nil {
		t.Fatal(err)
	}
	c, r, err := e.DialTCP(context.Background(), Metadata{Destination: netip.MustParseAddrPort(dst.Addr().String())})
	if c != nil || r.Kind != "Blocked" || err != ErrRejected || count.Load() != 0 {
		t.Fatal("reject opened a socket")
	}
}

func TestDirectTCPAndUDP(t *testing.T) {
	e, err := New([]byte("rules: ['MATCH,DIRECT']"))
	if err != nil {
		t.Fatal(err)
	}
	dst := fixture(t, func(c net.Conn) { _, _ = io.Copy(c, c) })
	c, r, err := e.DialTCP(context.Background(), Metadata{Destination: netip.MustParseAddrPort(dst.Addr().String())})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = c.Write([]byte("ok"))
	b := make([]byte, 2)
	_, err = io.ReadFull(c, b)
	if err != nil || string(b) != "ok" || r.Kind != "Direct" {
		t.Fatal("direct TCP failed")
	}
	u, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	go func() {
		b := make([]byte, 16)
		n, a, e := u.ReadFrom(b)
		if e == nil {
			_, _ = u.WriteTo(b[:n], a)
		}
	}()
	m := Metadata{Destination: netip.MustParseAddrPort(u.LocalAddr().String()), UDP: true}
	pc, r, err := e.OpenUDP(context.Background(), m)
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	_ = pc.SetDeadline(time.Now().Add(2 * time.Second))
	_, err = pc.WriteTo([]byte("udp"), net.UDPAddrFromAddrPort(m.Destination))
	if err != nil {
		t.Fatal(err)
	}
	b = make([]byte, 16)
	n, _, err := pc.ReadFrom(b)
	if err != nil || string(b[:n]) != "udp" || r.Kind != "Direct" {
		t.Fatalf("direct UDP failed: %v", err)
	}
}

func TestMihomoSOCKS5TCPAndUDP(t *testing.T) {
	u, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	go func() {
		b := make([]byte, 2048)
		n, a, e := u.ReadFrom(b)
		if e == nil && n >= 10 && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 {
			_, _ = u.WriteTo(b[:n], a)
		}
	}()
	proxy := fixture(t, func(c net.Conn) {
		var h [4]byte
		if _, e := io.ReadFull(c, h[:2]); e != nil || h[0] != 5 {
			return
		}
		methods := make([]byte, int(h[1]))
		if _, e := io.ReadFull(c, methods); e != nil {
			return
		}
		_, _ = c.Write([]byte{5, 0})
		if _, e := io.ReadFull(c, h[:]); e != nil || h[0] != 5 || h[3] != 1 {
			return
		}
		var addr [6]byte
		if _, e := io.ReadFull(c, addr[:]); e != nil {
			return
		}
		port := u.LocalAddr().(*net.UDPAddr).Port
		_, _ = c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
		if h[1] == 1 {
			_, _ = io.Copy(c, c)
		} else if h[1] == 3 {
			_, _ = io.Copy(io.Discard, c)
		}
	})
	p := fmt.Sprintf("proxies:\n - {name: socks, type: socks5, server: 127.0.0.1, port: %d, udp: true}\nrules: ['MATCH,socks']", proxy.Addr().(*net.TCPAddr).Port)
	e, err := New([]byte(p))
	if err != nil {
		t.Fatal(err)
	}
	m := Metadata{Destination: netip.MustParseAddrPort("192.0.2.1:53")}
	c, r, err := e.DialTCP(context.Background(), m)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = c.Write([]byte("tcp"))
	b := make([]byte, 3)
	_, err = io.ReadFull(c, b)
	if err != nil || string(b) != "tcp" || r.Kind != "Proxied" {
		t.Fatalf("SOCKS TCP failed: %v", err)
	}
	m.UDP = true
	pc, r, err := e.OpenUDP(context.Background(), m)
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	_ = pc.SetDeadline(time.Now().Add(2 * time.Second))
	_, err = pc.WriteTo([]byte("udp"), net.UDPAddrFromAddrPort(m.Destination))
	if err != nil {
		t.Fatal(err)
	}
	b = make([]byte, 128)
	n, a, err := pc.ReadFrom(b)
	if err != nil || string(b[:n]) != "udp" || a.String() != "192.0.2.1:53" || r.Kind != "Proxied" {
		t.Fatalf("SOCKS UDP failed: %v", err)
	}
}
