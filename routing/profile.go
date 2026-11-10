// SPDX-License-Identifier: GPL-3.0-or-later
// Package routing accepts a deliberately bounded Clash configuration subset.
// It has no listener, controller, subscription fetcher or inspection-store API.
package routing

import (
	"bytes"
	"errors"
	"io"
	"net/netip"
	"strconv"
	"strings"
	"unicode/utf8"

	"go.yaml.in/yaml/v3"
)

const MaxProfileBytes = 256 * 1024

var ErrProfile = errors.New("unsupported or invalid routing profile")
var ErrMetadata = errors.New("invalid live routing metadata")
var ErrRejected = errors.New("routing rule rejected connection")
var ErrTransport = errors.New("routing transport failed")

type proxySpec struct {
	Name     string `yaml:"name"`
	Type     string `yaml:"type"`
	Server   string `yaml:"server"`
	Port     int    `yaml:"port"`
	Username string `yaml:"username"`
	Password string `yaml:"password"`
	TLS      bool   `yaml:"tls"`
	UDP      bool   `yaml:"udp"`
}

type groupSpec struct {
	Name    string   `yaml:"name"`
	Type    string   `yaml:"type"`
	Proxies []string `yaml:"proxies"`
}

type profileSpec struct {
	Proxies []proxySpec `yaml:"proxies"`
	Groups  []groupSpec `yaml:"proxy-groups"`
	Rules   []string    `yaml:"rules"`
}

type rule struct {
	kind, value, target string
	prefix              netip.Prefix
}

// Profile is immutable after parsing; credentials are never returned by its API.
type Profile struct {
	proxies               map[string]proxySpec
	groups                map[string]string
	rules                 []rule
	needsHost, needsOwner bool
}

// Metadata contains only a currently allowed flow's routing inputs.
// Owner is Android user ID plus package (e.g. 0:com.example.app), not a PID/UID.
type Metadata struct {
	Destination netip.AddrPort
	Host        string
	Owner       string
	UDP         bool
}

type Route struct{ Kind, Name string }

func Parse(data []byte) (*Profile, error) {
	if len(data) == 0 || len(data) > MaxProfileBytes || !utf8.Valid(data) {
		return nil, ErrProfile
	}
	// Reject aliases/merges and complex nodes before typed decoding. yaml.v3 also
	// rejects duplicate keys; KnownFields prevents ignored security options.
	d := yaml.NewDecoder(bytes.NewReader(data))
	var tree yaml.Node
	if d.Decode(&tree) != nil || !boundedNode(&tree, 0, new(int)) {
		return nil, ErrProfile
	}
	var extra yaml.Node
	if d.Decode(&extra) != io.EOF {
		return nil, ErrProfile
	}
	d = yaml.NewDecoder(bytes.NewReader(data))
	d.KnownFields(true)
	var raw profileSpec
	if d.Decode(&raw) != nil || len(raw.Proxies) > 64 || len(raw.Groups) > 64 || len(raw.Rules) == 0 || len(raw.Rules) > 1000 {
		return nil, ErrProfile
	}
	p := &Profile{proxies: map[string]proxySpec{}, groups: map[string]string{}}
	used := map[string]bool{"DIRECT": true, "REJECT": true}
	for _, s := range raw.Proxies {
		if !label(s.Name) || used[s.Name] || (s.Type != "socks5" && s.Type != "http") || !server(s.Server) || s.Port < 1 || s.Port > 65535 || len(s.Username) > 256 || len(s.Password) > 1024 || (s.Type == "http" && s.UDP) {
			return nil, ErrProfile
		}
		used[s.Name] = true
		p.proxies[s.Name] = s
	}
	for _, g := range raw.Groups {
		if !label(g.Name) || used[g.Name] || g.Type != "select" || len(g.Proxies) == 0 || len(g.Proxies) > 64 {
			return nil, ErrProfile
		}
		used[g.Name] = true
		// Initial selection is explicit profile order. No health probe or failover.
		p.groups[g.Name] = g.Proxies[0]
	}
	for _, g := range raw.Groups {
		for _, n := range g.Proxies {
			if !used[n] {
				return nil, ErrProfile
			}
		}
	}
	for n := range p.groups {
		if _, ok := p.resolve(n); !ok {
			return nil, ErrProfile
		}
	}
	for i, text := range raw.Rules {
		parts := strings.Split(text, ",")
		if len(parts) < 2 || len(parts) > 4 {
			return nil, ErrProfile
		}
		for j := range parts {
			if parts[j] != strings.TrimSpace(parts[j]) || parts[j] == "" {
				return nil, ErrProfile
			}
		}
		r := rule{kind: parts[0]}
		if r.kind == "MATCH" {
			if len(parts) != 2 || i != len(raw.Rules)-1 {
				return nil, ErrProfile
			}
			r.target = parts[1]
		} else {
			if len(parts) != 3 && !(len(parts) == 4 && parts[3] == "no-resolve" && (r.kind == "IP-CIDR" || r.kind == "IP-CIDR6")) {
				return nil, ErrProfile
			}
			r.value = parts[1]
			r.target = parts[2]
			switch r.kind {
			case "DOMAIN", "DOMAIN-SUFFIX":
				if !domain(r.value) {
					return nil, ErrProfile
				}
				p.needsHost = true
			case "IP-CIDR", "IP-CIDR6":
				var err error
				r.prefix, err = netip.ParsePrefix(r.value)
				if err != nil || r.prefix != r.prefix.Masked() || (r.kind == "IP-CIDR") != r.prefix.Addr().Is4() {
					return nil, ErrProfile
				}
			case "PROCESS-NAME":
				if !packageName(r.value) {
					return nil, ErrProfile
				}
				p.needsOwner = true
			default:
				return nil, ErrProfile
			}
		}
		if !used[r.target] {
			return nil, ErrProfile
		}
		p.rules = append(p.rules, r)
	}
	if p.rules[len(p.rules)-1].kind != "MATCH" {
		return nil, ErrProfile
	}
	return p, nil
}

func boundedNode(n *yaml.Node, depth int, count *int) bool {
	*count++
	if depth > 8 || *count > 14000 || n.Kind == yaml.AliasNode || n.Anchor != "" || len(n.Value) > 4096 || n.Tag == "!!merge" {
		return false
	}
	if n.Kind == yaml.MappingNode {
		for i := 0; i < len(n.Content); i += 2 {
			if n.Content[i].Kind != yaml.ScalarNode || n.Content[i].Tag != "!!str" {
				return false
			}
		}
	}
	for _, c := range n.Content {
		if !boundedNode(c, depth+1, count) {
			return false
		}
	}
	return true
}

func label(s string) bool {
	return s != "" && len(s) <= 128 && !strings.ContainsAny(s, ",\r\n\x00") && s == strings.TrimSpace(s)
}
func server(s string) bool {
	if a, e := netip.ParseAddr(s); e == nil {
		return a.Zone() == "" && !a.IsUnspecified() && !a.IsMulticast()
	}
	// Initial Android adapter deliberately has no proxy-bootstrap DNS resolver.
	return false
}
func domain(s string) bool {
	if len(s) == 0 || len(s) > 253 || s != strings.ToLower(s) {
		return false
	}
	for _, l := range strings.Split(s, ".") {
		if len(l) == 0 || len(l) > 63 || l[0] == '-' || l[len(l)-1] == '-' {
			return false
		}
		for _, c := range l {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return false
			}
		}
	}
	return true
}
func packageName(s string) bool {
	if len(s) > 255 || !strings.Contains(s, ".") {
		return false
	}
	for _, v := range strings.Split(s, ".") {
		if v == "" {
			return false
		}
		for i, c := range v {
			if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || i > 0 && c >= '0' && c <= '9') {
				return false
			}
		}
	}
	return true
}
func validOwner(s string) bool {
	a, b, ok := strings.Cut(s, ":")
	if !ok || !packageName(b) {
		return false
	}
	u, e := strconv.ParseUint(a, 10, 31)
	return e == nil && strconv.FormatUint(u, 10) == a
}
func (p *Profile) resolve(n string) (string, bool) {
	for i := 0; i <= len(p.groups); i++ {
		next, ok := p.groups[n]
		if !ok {
			return n, true
		}
		n = next
	}
	return "", false
}
func (p *Profile) NeedsHost() bool  { return p.needsHost }
func (p *Profile) NeedsOwner() bool { return p.needsOwner }

// Select performs no DNS lookup. Unknown attribution/hostname cannot match a
// corresponding rule and remains unknown; the explicit MATCH rule then applies.
func (p *Profile) Select(m Metadata) (Route, error) {
	if !m.Destination.IsValid() || m.Destination.Port() == 0 || m.Destination.Addr().Zone() != "" || m.Destination.Addr().IsUnspecified() || m.Destination.Addr().IsMulticast() || (m.Host != "" && !domain(m.Host)) || (m.Owner != "" && !validOwner(m.Owner)) {
		return Route{}, ErrMetadata
	}
	for _, r := range p.rules {
		matched := false
		switch r.kind {
		case "MATCH":
			matched = true
		case "DOMAIN":
			matched = m.Host == r.value
		case "DOMAIN-SUFFIX":
			matched = m.Host == r.value || strings.HasSuffix(m.Host, "."+r.value)
		case "PROCESS-NAME":
			_, pkg, _ := strings.Cut(m.Owner, ":")
			matched = pkg == r.value
		case "IP-CIDR", "IP-CIDR6":
			matched = r.prefix.Contains(m.Destination.Addr())
		}
		if matched {
			n, _ := p.resolve(r.target)
			kind := "Proxied"
			if n == "DIRECT" {
				kind = "Direct"
			}
			if n == "REJECT" {
				kind = "Blocked"
			}
			return Route{Kind: kind, Name: n}, nil
		}
	}
	return Route{}, ErrProfile
}
