// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"net/netip"
	"strings"
	"testing"
)

const sample = `proxies:
  - name: local-proxy
    type: socks5
    server: 127.0.0.1
    port: 1080
    udp: true
proxy-groups:
  - name: selected
    type: select
    proxies: [local-proxy, DIRECT]
rules:
  - PROCESS-NAME,com.example.app,selected
  - DOMAIN,example.com,REJECT
  - DOMAIN-SUFFIX,example.net,selected
  - IP-CIDR,192.0.2.0/24,DIRECT,no-resolve
  - IP-CIDR6,2001:db8::/32,REJECT,no-resolve
  - MATCH,DIRECT
`

func TestSelection(t *testing.T) {
	p, err := Parse([]byte(sample))
	if err != nil {
		t.Fatal(err)
	}
	if !p.NeedsHost() || !p.NeedsOwner() {
		t.Fatal("missing metadata requirements")
	}
	for _, c := range []struct{ ip, host, owner, kind, name string }{
		{"203.0.113.1:443", "example.com", "0:com.example.app", "Proxied", "local-proxy"},
		{"203.0.113.1:443", "example.com", "", "Blocked", "REJECT"},
		{"203.0.113.1:443", "www.example.net", "", "Proxied", "local-proxy"},
		{"203.0.113.1:443", "badexample.net", "", "Direct", "DIRECT"},
		{"192.0.2.1:443", "", "", "Direct", "DIRECT"},
		{"[2001:db8::1]:443", "", "", "Blocked", "REJECT"},
		{"203.0.113.1:443", "", "", "Direct", "DIRECT"},
		{"203.0.113.1:443", "", "10:com.example.app", "Proxied", "local-proxy"},
	} {
		r, e := p.Select(Metadata{Destination: netip.MustParseAddrPort(c.ip), Host: c.host, Owner: c.owner})
		if e != nil || r.Kind != c.kind || r.Name != c.name {
			t.Fatalf("route mismatch: %v %v", r, e)
		}
	}
}

func TestRejectUnsafeProfiles(t *testing.T) {
	for _, s := range []string{
		sample + "external-controller: 0.0.0.0:9090\n",
		sample + "proxy-providers: {}\n", sample + "dns: {}\n", sample + "tun: {}\n",
		sample + "script: {}\n", sample + "rules: [MATCH,DIRECT]\n",
		strings.Replace(sample, "type: socks5", "type: vless", 1),
		strings.Replace(sample, "port: 1080", "port: 0", 1),
		strings.Replace(sample, "port: 1080", "port: 1080\n    private-key: secret.pem", 1),
		strings.Replace(sample, "port: 1080", "port: 1080\n    skip-cert-verify: true", 1),
		strings.Replace(sample, "server: 127.0.0.1", "server: file:///private/data", 1),
		strings.Replace(sample, "type: select", "type: url-test", 1),
		strings.Replace(sample, "[local-proxy, DIRECT]", "[selected, DIRECT]", 1),
		strings.Replace(sample, "[local-proxy, DIRECT]", "[missing]", 1),
		strings.Replace(sample, "DOMAIN,example.com", "DOMAIN,EXAMPLE.COM", 1),
		strings.Replace(sample, "DOMAIN,example.com", "GEOIP,CN", 1),
		strings.Replace(sample, "192.0.2.0/24", "192.0.2.1/24", 1),
		strings.Replace(sample, "MATCH,DIRECT", "MATCH,unknown", 1),
		strings.Replace(sample, "MATCH,DIRECT", "DOMAIN,other.test,DIRECT", 1),
		"rules: &r [MATCH,DIRECT]\nproxies: *r\n",
		sample + "---\nrules: [MATCH,REJECT]\n",
		string([]byte{0xff, 0xfe}), strings.Repeat(" ", MaxProfileBytes+1),
	} {
		if _, e := Parse([]byte(s)); e != ErrProfile {
			t.Fatal("accepted unsupported profile")
		}
	}
}

func TestInvalidMetadata(t *testing.T) {
	p, _ := Parse([]byte(sample))
	for _, m := range []Metadata{
		{}, {Destination: netip.MustParseAddrPort("127.0.0.1:0")},
		{Destination: netip.MustParseAddrPort("0.0.0.0:80")},
		{Destination: netip.MustParseAddrPort("224.0.0.1:80")},
		{Destination: netip.MustParseAddrPort("127.0.0.1:80"), Host: "bad\nname"},
		{Destination: netip.MustParseAddrPort("127.0.0.1:80"), Owner: "01:com.example.app"},
		{Destination: netip.MustParseAddrPort("127.0.0.1:80"), Owner: "com.example.app"},
	} {
		if _, e := p.Select(m); e != ErrMetadata {
			t.Fatal("accepted invalid metadata")
		}
	}
}

func FuzzProfile(f *testing.F) {
	f.Add([]byte(sample))
	f.Add([]byte("rules: ['MATCH,DIRECT']"))
	f.Fuzz(func(t *testing.T, b []byte) {
		p, e := Parse(b)
		if e == nil {
			_, _ = p.Select(Metadata{Destination: netip.MustParseAddrPort("127.0.0.1:80")})
		}
	})
}
