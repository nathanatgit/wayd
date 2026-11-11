// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// An HTTP CONNECT node on loopback that records the request target and then echoes.
func connectNode(t *testing.T) (port int, targets chan string) {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { l.Close() })
	targets = make(chan string, 4)
	go func() {
		for {
			c, e := l.Accept()
			if e != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				r := bufio.NewReader(c)
				line, e := r.ReadString('\n')
				if e != nil {
					return
				}
				for {
					h, e := r.ReadString('\n')
					if e != nil || h == "\r\n" {
						break
					}
				}
				targets <- strings.Fields(line)[1]
				_, _ = io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n")
				_, _ = io.Copy(c, r)
			}(c)
		}
	}()
	return l.Addr().(*net.TCPAddr).Port, targets
}

func connectEngine(t *testing.T, port int, rules string) *Engine {
	source := fmt.Sprintf("proxies:\n - {name: node, type: http, server: 127.0.0.1, port: %d}\nrules: [%s]\n", port, rules)
	b, _ := encodeBundle(ExtensionBundle{Source: []byte(source), Mode: "rule"})
	e, err := New(b)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Close)
	return e
}

func TestDialNameSendsTheNameToTheNodeNotALocalAnswer(t *testing.T) {
	port, targets := connectNode(t)
	e := connectEngine(t, port, "'DOMAIN-SUFFIX,googleapis.com,node','MATCH,REJECT'")
	c, r, err := e.DialName(context.Background(), "generativelanguage.googleapis.com", 443, "0:com.nathanhanapps.wayd")
	if err != nil || r.Kind != "Proxied" || r.Name != "node" {
		t.Fatal(r, err)
	}
	defer c.Close()
	select {
	case got := <-targets:
		if got != "generativelanguage.googleapis.com:443" {
			t.Fatal("node must resolve the name itself, got", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("node never saw the request")
	}
	_, _ = c.Write([]byte("ping"))
	b := make([]byte, 4)
	_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := io.ReadFull(c, b); err != nil || string(b) != "ping" {
		t.Fatal("stream does not carry bytes", err)
	}
}

func TestDialNameFollowsOwnerRulesAndRejects(t *testing.T) {
	port, _ := connectNode(t)
	e := connectEngine(t, port, "'PROCESS-NAME,com.nathanhanapps.wayd,node','MATCH,REJECT'")
	c, r, err := e.DialName(context.Background(), "api.example.net", 443, "0:com.nathanhanapps.wayd")
	if err != nil || r.Kind != "Proxied" {
		t.Fatal("owner rule must route WAYD's call", r, err)
	}
	c.Close()
	if _, r, err = e.DialName(context.Background(), "api.example.net", 443, "0:com.other.app"); !errors.Is(err, ErrRejected) || r.Kind != "Blocked" {
		t.Fatal("other owners fall to MATCH,REJECT", r, err)
	}
}

func TestDialNameRejectsBadInputAndLegacyProfiles(t *testing.T) {
	port, _ := connectNode(t)
	e := connectEngine(t, port, "'MATCH,node'")
	for _, h := range []string{"", "Upper.Example.com", "1.2.3.4:443", "bad host"} {
		if _, _, err := e.DialName(context.Background(), h, 443, ""); !errors.Is(err, ErrMetadata) {
			t.Fatal("accepted host", h, err)
		}
	}
	if _, _, err := e.DialName(context.Background(), "api.example.net", 0, ""); !errors.Is(err, ErrMetadata) {
		t.Fatal("accepted port 0")
	}
	if _, _, err := e.DialName(context.Background(), "api.example.net", 443, "not an owner"); !errors.Is(err, ErrMetadata) {
		t.Fatal("accepted owner")
	}
	legacy, err := New([]byte(fmt.Sprintf("proxies:\n  - {name: p, type: socks5, server: 127.0.0.1, port: %d}\nproxy-groups: []\nrules:\n  - MATCH,p\n", port)))
	if err != nil {
		t.Skip("legacy fixture not accepted:", err)
	}
	defer legacy.Close()
	if _, _, err := legacy.DialName(context.Background(), "api.example.net", 443, ""); !errors.Is(err, ErrMetadata) {
		t.Fatal("legacy profiles have no name routing and must fail closed", err)
	}
}
