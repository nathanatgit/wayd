// SPDX-License-Identifier: GPL-3.0-or-later
package routing

// The extension compiles ordinary Clash profiles without starting Mihomo's
// listeners, TUN, controller, remote UI or inspection machinery. Resources are
// supplied by the explicitly initiated Android import/update operation.
import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	R "github.com/metacubex/mihomo/rules"
	RC "github.com/metacubex/mihomo/rules/common"
	RP "github.com/metacubex/mihomo/rules/provider"
	"github.com/oschwald/maxminddb-golang"
	"go.yaml.in/yaml/v3"
)

const extensionPrefix = "WAYD-CLASH-1\n"
const MaxExtensionBytes = 48 * 1024 * 1024
const MaxSourceBytes = 4 * 1024 * 1024

type ExtensionBundle struct {
	Source     []byte            `json:"source"`
	Resources  map[string][]byte `json:"resources"`
	Selections map[string]string `json:"selections"`
	Mode       string            `json:"mode"`
	Delays     map[string]int    `json:"delays"`
}
type ResourceRequest struct {
	Key  string `json:"key"`
	URL  string `json:"url"`
	Kind string `json:"kind"`
}
type ExtensionOverview struct {
	Proxies   []map[string]any  `json:"proxies"`
	Groups    []map[string]any  `json:"groups"`
	Rules     []string          `json:"rules"`
	Resources []ResourceRequest `json:"resources"`
	Overrides []string          `json:"overrides"`
	Mode      string            `json:"mode"`
}
type extensionRule struct {
	target string
	match  func(*C.Metadata) bool
}
type extensionEngine struct {
	proxies map[string]C.Proxy
	rules   []extensionRule
	mode    string
	geo     *maxminddb.Reader
}

func decodeSource(data []byte) (map[string]any, error) {
	return decodeYaml(data, MaxSourceBytes)
}

func decodeYaml(data []byte, limit int) (map[string]any, error) {
	if len(data) == 0 || len(data) > limit || !utf8.Valid(data) {
		return nil, ErrProfile
	}
	d := yaml.NewDecoder(bytes.NewReader(data))
	var tree yaml.Node
	if d.Decode(&tree) != nil {
		return nil, ErrProfile
	}
	count := 0
	var check func(*yaml.Node, int) bool
	check = func(n *yaml.Node, depth int) bool {
		count++
		if count > 250000 || depth > 32 || len(n.Value) > 65536 {
			return false
		}
		if n.Kind == yaml.AliasNode {
			return n.Alias != nil && check(n.Alias, depth+1)
		}
		for _, v := range n.Content {
			if !check(v, depth+1) {
				return false
			}
		}
		return true
	}
	if !check(&tree, 0) {
		return nil, ErrProfile
	}
	var extra yaml.Node
	if d.Decode(&extra) != io.EOF {
		return nil, ErrProfile
	}
	var raw map[string]any
	if tree.Decode(&raw) != nil {
		return nil, ErrProfile
	}
	return raw, nil
}
func decodeBundle(data []byte) (ExtensionBundle, error) {
	var b ExtensionBundle
	if bytes.HasPrefix(data, []byte(extensionPrefix)) {
		if len(data) > MaxExtensionBytes || json.Unmarshal(data[len(extensionPrefix):], &b) != nil {
			return b, ErrProfile
		}
	} else {
		b.Source = data
	}
	if b.Resources == nil {
		b.Resources = map[string][]byte{}
	}
	if b.Selections == nil {
		b.Selections = map[string]string{}
	}
	if b.Mode == "" {
		b.Mode = "rule"
	}
	if b.Mode != "rule" && b.Mode != "global" && b.Mode != "direct" {
		return b, ErrProfile
	}
	return b, nil
}
func encodeBundle(b ExtensionBundle) ([]byte, error) {
	v, e := json.Marshal(b)
	if e != nil || len(v)+len(extensionPrefix) > MaxExtensionBytes {
		return nil, ErrProfile
	}
	return append([]byte(extensionPrefix), v...), nil
}
func objects(v any) ([]map[string]any, error) {
	if v == nil {
		return nil, nil
	}
	a, ok := v.([]any)
	if !ok || len(a) > 10000 {
		return nil, ErrProfile
	}
	out := make([]map[string]any, 0, len(a))
	for _, i := range a {
		m, ok := i.(map[string]any)
		if !ok {
			return nil, ErrProfile
		}
		out = append(out, m)
	}
	return out, nil
}
func stringsOf(v any) ([]string, error) {
	if v == nil {
		return nil, nil
	}
	a, ok := v.([]any)
	if !ok || len(a) > 200000 {
		return nil, ErrProfile
	}
	out := make([]string, 0, len(a))
	for _, i := range a {
		s, ok := i.(string)
		if !ok {
			return nil, ErrProfile
		}
		out = append(out, s)
	}
	return out, nil
}
func resourceRequests(raw map[string]any) ([]ResourceRequest, error) {
	out := []ResourceRequest{}
	for _, kind := range []string{"rule-providers", "proxy-providers"} {
		if raw[kind] == nil {
			continue
		}
		all, ok := raw[kind].(map[string]any)
		if !ok || len(all) > 256 {
			return nil, ErrProfile
		}
		for name, v := range all {
			m, ok := v.(map[string]any)
			if !ok {
				return nil, ErrProfile
			}
			if m["type"] == "inline" {
				continue
			}
			u, _ := m["url"].(string)
			parsed, e := url.Parse(u)
			if e != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil {
				return nil, errors.New("provider_requires_https")
			}
			out = append(out, ResourceRequest{kind + ":" + name, u, kind})
		}
	}
	rules, _ := stringsOf(raw["rules"])
	for _, r := range rules {
		if strings.HasPrefix(r, "GEOIP,") && !strings.HasPrefix(strings.ToLower(r), "geoip,lan,") {
			geoURL := "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/country.mmdb"
			if urls, ok := raw["geox-url"].(map[string]any); ok {
				if u, ok := urls["mmdb"].(string); ok {
					geoURL = u
				}
			}
			parsed, e := url.Parse(geoURL)
			if e != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil {
				return nil, ErrProfile
			}
			out = append(out, ResourceRequest{"geoip", geoURL, "geoip"})
			break
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out, nil
}
func InspectExtension(data []byte) (ExtensionOverview, error) {
	b, e := decodeBundle(data)
	if e != nil {
		return ExtensionOverview{}, e
	}
	raw, e := decodeSource(b.Source)
	if e != nil {
		return ExtensionOverview{}, e
	}
	o := ExtensionOverview{Proxies: []map[string]any{}, Groups: []map[string]any{}, Overrides: []string{}, Mode: b.Mode}
	o.Resources, e = resourceRequests(raw)
	if e != nil {
		return o, e
	}
	ps, e := objects(raw["proxies"])
	if e != nil {
		return o, e
	}
	gs, e := objects(raw["proxy-groups"])
	if e != nil {
		return o, e
	}
	for _, p := range ps {
		o.Proxies = append(o.Proxies, map[string]any{"name": p["name"], "type": p["type"]})
	}
	for _, g := range gs {
		members, e := stringsOf(g["proxies"])
		if e != nil {
			return o, e
		}
		selected := b.Selections[stringValue(g["name"])]
		if selected == "" && len(members) > 0 {
			selected = members[0]
		}
		o.Groups = append(o.Groups, map[string]any{"name": g["name"], "type": g["type"], "members": members, "selected": selected})
	}
	o.Rules, e = stringsOf(raw["rules"])
	if e != nil {
		return o, e
	}
	for _, key := range []string{"port", "socks-port", "mixed-port", "redir-port", "tproxy-port", "allow-lan", "tun", "dns", "external-controller", "external-ui", "listeners", "sniffer", "profile", "ntp"} {
		if _, ok := raw[key]; ok {
			o.Overrides = append(o.Overrides, key)
		}
	}
	return o, nil
}
func stringValue(v any) string { s, _ := v.(string); return s }

// Reject profile-supplied local filesystem references throughout proxy options.
// Network paths inside ws/grpc options are protocol data, not filesystem paths.
func safeProxy(v any) bool {
	switch v := v.(type) {
	case map[string]any:
		for k, x := range v {
			switch k {
			case "private-key-path", "certificate-path", "ca", "ca-str", "client-fingerprint-path", "plugin-path", "script", "interface-name", "routing-mark", "dialer-proxy":
				return false
			}
			if !safeProxy(x) {
				return false
			}
		}
	case []any:
		for _, x := range v {
			if !safeProxy(x) {
				return false
			}
		}
	}
	return true
}
func newExtension(data []byte) (engine *Engine, err error) {
	stage := "source"
	defer func() {
		if errors.Is(err, ErrProfile) {
			err = errors.New("invalid_" + stage)
		}
	}()
	b, err := decodeBundle(data)
	if err != nil {
		return nil, err
	}
	raw, err := decodeSource(b.Source)
	if err != nil {
		return nil, err
	}
	requests, err := resourceRequests(raw)
	if err != nil {
		return nil, err
	}
	for _, r := range requests {
		sharedGeo.RLock()
		geoReady := sharedGeo.reader != nil
		sharedGeo.RUnlock()
		if len(b.Resources[r.Key]) == 0 && !(r.Key == "geoip" && geoReady) {
			return nil, errors.New("resources_required")
		}
	}
	x := &extensionEngine{proxies: map[string]C.Proxy{}, mode: b.Mode}
	sharedGeo.RLock()
	x.geo = sharedGeo.reader
	sharedGeo.RUnlock()
	success := false
	defer func() {
		if !success {
			x.close()
		}
	}()
	if data := b.Resources["geoip"]; len(data) > 0 {
		x.geo, err = maxminddb.FromBytes(data)
		if err != nil {
			return nil, ErrProfile
		}
	}
	for _, s := range []struct {
		name string
		a    C.ProxyAdapter
	}{{"DIRECT", outbound.NewDirect()}, {"REJECT", outbound.NewReject()}, {"REJECT-DROP", outbound.NewRejectDrop()}, {"COMPATIBLE", outbound.NewReject()}} {
		x.proxies[s.name] = adapter.NewProxy(s.a)
	}
	stage = "proxies"
	ps, err := objects(raw["proxies"])
	if err != nil {
		return nil, err
	}
	providerMembers := map[string][]string{}
	if pds, ok := raw["proxy-providers"].(map[string]any); ok {
		for name, v := range pds {
			m := v.(map[string]any)
			var provided []map[string]any
			if m["type"] == "inline" {
				provided, err = objects(m["payload"])
			} else {
				source, e := decodeYaml(b.Resources["proxy-providers:"+name], 8*1024*1024)
				if e != nil {
					return nil, e
				}
				provided, err = objects(source["proxies"])
			}
			if err != nil {
				return nil, err
			}
			for _, p := range provided {
				providerMembers[name] = append(providerMembers[name], stringValue(p["name"]))
				ps = append(ps, p)
			}
		}
	}
	if len(ps) > 10000 {
		return nil, ErrProfile
	}
	all := []string{}
	for _, m := range ps {
		if !safeProxy(m) {
			return nil, errors.New("local_proxy_options_unsupported")
		}
		name := stringValue(m["name"])
		if !label(name) || x.proxies[name] != nil {
			return nil, ErrProfile
		}
		p, e := adapter.ParseProxy(m)
		if e != nil {
			return nil, errors.New("invalid_proxy_options")
		}
		if delay, ok := b.Delays[name]; ok {
			p = &measuredProxy{Proxy: p, delay: delay}
		}
		x.proxies[name] = p
		all = append(all, name)
	}
	stage = "groups"
	gs, err := objects(raw["proxy-groups"])
	if err != nil {
		return nil, err
	}
	pds := map[string]P.ProxyProvider{}
	// Topological construction preserves nested group semantics and rejects cycles.
	pending := gs
	for len(pending) > 0 {
		next := []map[string]any{}
		progress := false
		for _, g := range pending {
			name := stringValue(g["name"])
			if !label(name) || x.proxies[name] != nil {
				return nil, ErrProfile
			}
			members, e := stringsOf(g["proxies"])
			if e != nil {
				return nil, e
			}
			use, e := stringsOf(g["use"])
			if e != nil {
				return nil, e
			}
			for _, pd := range use {
				v, ok := providerMembers[pd]
				if !ok {
					return nil, ErrProfile
				}
				members = append(members, v...)
			}
			if g["include-all"] == true || g["include-all-proxies"] == true {
				members = append(members, all...)
			}
			if g["include-all"] == true || g["include-all-providers"] == true {
				for _, list := range providerMembers {
					members = append(members, list...)
				}
			}
			if len(members) == 0 {
				return nil, ErrProfile
			}
			ready := true
			for _, n := range members {
				if x.proxies[n] == nil {
					ready = false
				}
			}
			if !ready {
				next = append(next, g)
				continue
			}
			members, err = filterMembers(g, members, x.proxies)
			if err != nil || len(members) == 0 {
				return nil, ErrProfile
			}
			copyG := map[string]any{}
			for k, v := range g {
				copyG[k] = v
			}
			delete(copyG, "filter")
			delete(copyG, "exclude-filter")
			delete(copyG, "exclude-type")
			delete(copyG, "use")
			delete(copyG, "include-all")
			delete(copyG, "include-all-proxies")
			delete(copyG, "include-all-providers")
			a := make([]any, len(members))
			for i, n := range members {
				a[i] = n
			}
			copyG["proxies"] = a
			// A group with no usable nodes fails closed rather than choosing Direct.
			copyG["empty-fallback"] = "REJECT"
			copyG["interval"] = 0
			group, e := outboundgroup.ParseProxyGroup(copyG, x.proxies, pds, all, nil)
			if e != nil {
				return nil, errors.New("invalid_proxy_group")
			}
			if selected := b.Selections[name]; selected != "" {
				sel, ok := group.(interface{ Set(string) error })
				if !ok || sel.Set(selected) != nil {
					return nil, ErrProfile
				}
			}
			x.proxies[name] = adapter.NewProxy(group)
			progress = true
		}
		if !progress {
			return nil, errors.New("group_reference_or_cycle")
		}
		pending = next
	}
	if x.proxies["GLOBAL"] == nil {
		members := make([]any, len(all))
		for i, n := range all {
			members[i] = n
		}
		if len(members) == 0 {
			members = []any{"DIRECT"}
		}
		g, e := outboundgroup.ParseProxyGroup(map[string]any{"name": "GLOBAL", "type": "select", "proxies": members, "empty-fallback": "REJECT"}, x.proxies, pds, all, nil)
		if e != nil {
			return nil, ErrProfile
		}
		if s := b.Selections["GLOBAL"]; s != "" {
			if g.(*outboundgroup.Selector).Set(s) != nil {
				return nil, ErrProfile
			}
		}
		x.proxies["GLOBAL"] = adapter.NewProxy(g)
	}
	stage = "rule_provider_payload"
	sets := map[string]func(*C.Metadata) bool{}
	if providers, ok := raw["rule-providers"].(map[string]any); ok {
		for name, v := range providers {
			p := v.(map[string]any)
			var payload []string
			if p["type"] == "inline" {
				payload, err = stringsOf(p["payload"])
			} else {
				data := b.Resources["rule-providers:"+name]
				format := stringValue(p["format"])
				if format == "text" {
					for _, line := range strings.Split(string(data), "\n") {
						line = strings.TrimSpace(line)
						if line != "" && !strings.HasPrefix(line, "#") {
							payload = append(payload, line)
						}
					}
				} else if format == "m rs" || format == "mrs" {
					return nil, errors.New("mrs_resource_unsupported")
				} else {
					source, e := decodeYaml(data, 8*1024*1024)
					if e != nil {
						return nil, e
					}
					payload, err = stringsOf(source["payload"])
				}
			}
			if err != nil {
				return nil, err
			}
			if len(payload) > 200000 {
				return nil, ErrProfile
			}
			behavior := stringValue(p["behavior"])
			if behavior == "domain" || behavior == "ipcidr" {
				var strategy interface {
					Match(*C.Metadata, C.RuleMatchHelper) bool
					Count() int
					Reset()
					Insert(string)
					FinishInsert()
				}
				if behavior == "domain" {
					strategy = RP.NewDomainStrategy()
				} else {
					strategy = RP.NewIPCidrStrategy()
				}
				strategy.Reset()
				for _, line := range payload {
					strategy.Insert(line)
				}
				if strategy.Count() != len(payload) {
					return nil, errors.New("invalid_rule_provider_entry")
				}
				strategy.FinishInsert()
				sets[name] = func(m *C.Metadata) bool { return strategy.Match(m, C.RuleMatchHelper{}) }
			} else if behavior == "classical" {
				compiled := []extensionRule{}
				for _, entry := range payload {
					kind, value, _, opts := RC.ParseRulePayload(entry, false)
					r, e := x.compile(kind, value, "", opts)
					if e != nil {
						return nil, e
					}
					compiled = append(compiled, r)
				}
				sets[name] = func(m *C.Metadata) bool {
					for _, r := range compiled {
						if r.match(m) {
							return true
						}
					}
					return false
				}
			} else {
				return nil, ErrProfile
			}

		}
	}
	stage = "rules"
	lines, err := stringsOf(raw["rules"])
	if err != nil || len(lines) == 0 {
		return nil, ErrProfile
	}
	for _, line := range lines {
		tp, payload, target, params := RC.ParseRulePayload(line, true)
		if x.proxies[target] == nil {
			return nil, ErrProfile
		}
		if tp == "RULE-SET" {
			entries, ok := sets[payload]
			if !ok {
				return nil, ErrProfile
			}
			x.rules = append(x.rules, extensionRule{target, entries})
		} else {
			r, e := x.compile(tp, payload, target, params)
			if e != nil {
				return nil, e
			}
			x.rules = append(x.rules, r)
		}
	}
	// Unlike Clash's implicit Direct default, require explicit final policy.
	if !strings.HasPrefix(lines[len(lines)-1], "MATCH,") {
		return nil, errors.New("final_match_required")
	}
	adapters := map[string]C.ProxyAdapter{}
	for name, p := range x.proxies {
		adapters[name] = p
	}
	success = true
	return &Engine{adapters: adapters, extension: x}, nil
}
func (x *extensionEngine) compile(tp, payload, target string, params []string) (extensionRule, error) {
	if tp == "GEOIP" {
		code := strings.ToLower(payload)
		if code != "lan" && x.geo == nil {
			return extensionRule{}, errors.New("geoip_resource_required")
		}
		return extensionRule{target, func(m *C.Metadata) bool {
			ip := m.DstIP
			if code == "lan" {
				return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast()
			}
			var result any
			if x.geo.Lookup(net.IP(ip.AsSlice()), &result) != nil {
				return false
			}
			switch r := result.(type) {
			case string:
				return strings.EqualFold(r, code)
			case []any:
				for _, v := range r {
					if s, ok := v.(string); ok && strings.EqualFold(s, code) {
						return true
					}
				}
			case map[string]any:
				if c, ok := r["country"].(map[string]any); ok {
					return strings.EqualFold(stringValue(c["iso_code"]), code)
				}
			}
			return false
		}}, nil
	}
	switch tp {
	case "GEOSITE", "IP-ASN", "SRC-IP-ASN", "SRC-GEOIP", "RULE-SET", "SUB-RULE":
		return extensionRule{}, errors.New("rule_resource_or_metadata_unsupported")
	}
	r, err := R.ParseRule(tp, payload, target, params, nil)
	if err != nil {
		return extensionRule{}, errors.New("invalid_rule")
	}
	return extensionRule{target, func(m *C.Metadata) bool { ok, _ := r.Match(m, C.RuleMatchHelper{}); return ok }}, nil
}
func (x *extensionEngine) selectRoute(m Metadata) (Route, error) {
	if !m.Destination.IsValid() || m.Destination.Port() == 0 || (m.Host != "" && !domain(m.Host)) || (m.Owner != "" && !validOwner(m.Owner)) {
		return Route{}, ErrMetadata
	}
	meta := liveMetadata(m)
	meta.Host = m.Host
	_, meta.Process, _ = strings.Cut(m.Owner, ":")
	meta.Type = C.TUN
	return x.resolve(meta)
}

// resolve applies the mode, the ordered rules and the group selection to one connection's metadata.
func (x *extensionEngine) resolve(meta *C.Metadata) (Route, error) {
	target := ""
	switch x.mode {
	case "direct":
		target = "DIRECT"
	case "global":
		target = "GLOBAL"
	default:
		for _, r := range x.rules {
			if r.match(meta) {
				target = r.target
				break
			}
		}
	}
	p := x.proxies[target]
	if p == nil {
		return Route{}, ErrProfile
	}
	// Resolve the group once. A failed leaf dial never re-selects Direct.
	for i := 0; i < 64; i++ {
		next := p.Unwrap(meta, false)
		if next == nil {
			kind := "Proxied"
			switch p.Type() {
			case C.Direct:
				kind = "Direct"
			case C.Reject, C.RejectDrop:
				kind = "Blocked"
			}
			return Route{kind, p.Name()}, nil
		}
		p = next
	}
	return Route{}, ErrProfile
}
func (x *extensionEngine) close() {
	for _, p := range x.proxies {
		_ = p.Close()
	}
}

// ExtensionCommand is a private in-process API, never a network controller.
// Responses contain metadata only except the explicit bundle edit commands.
func ExtensionCommand(profile, request []byte) (result []byte) {
	defer func() {
		if recover() != nil {
			result = []byte(`{"error":"invalid_profile"}`)
		}
	}()
	if v, ok := geoCommand(request); ok {
		return v
	}
	fail := func(code string) []byte { v, _ := json.Marshal(map[string]any{"error": code}); return v }
	var q struct {
		Action     string            `json:"action"`
		ID         string            `json:"id"`
		URL        string            `json:"url"`
		ViaProxy   bool              `json:"viaProxy"`
		Limit      int               `json:"limit"`
		Resources  map[string][]byte `json:"resources"`
		Selections map[string]string `json:"selections"`
		Mode       string            `json:"mode"`
		Delays     map[string]int    `json:"delays"`
	}
	if len(request) > MaxExtensionBytes || json.Unmarshal(request, &q) != nil {
		return fail("invalid_request")
	}
	if q.Action == "cancel-download" {
		cancelResourceDownload(q.ID)
		return []byte(`{"ok":true}`)
	}
	b, e := decodeBundle(profile)
	if e != nil {
		return fail("invalid_profile")
	}
	if q.Action == "download" {
		content, e := downloadResourceWithID(profile, q.URL, q.ViaProxy, q.Limit, q.ID)
		if e != nil {
			return fail(e.Error())
		}
		v, _ := json.Marshal(map[string]any{"body": content})
		return v
	}
	if q.Action == "inspect" {
		o, e := InspectExtension(profile)
		if e != nil {
			return fail("invalid_profile")
		}
		v, _ := json.Marshal(o)
		return v
	}
	if q.Action == "latency" {
		data, e := encodeBundle(b)
		if e != nil {
			return fail("invalid_profile")
		}
		engine, e := newExtension(data)
		if e != nil {
			return fail("invalid_profile")
		}
		defer engine.Close()
		delays, e := testNodes(engine, q.URL)
		if e != nil {
			return fail("latency_failed")
		}
		b.Delays = delays
		data, e = encodeBundle(b)
		if e != nil {
			return fail("profile_too_large")
		}
		v, _ := json.Marshal(map[string]any{"bundle": data, "delays": delays})
		return v
	}
	if q.Action == "prepare" || q.Action == "configure" {
		for k, v := range q.Resources {
			b.Resources[k] = v
		}
		for k, v := range q.Selections {
			b.Selections[k] = v
		}
		if q.Mode != "" {
			b.Mode = q.Mode
		}
		data, e := encodeBundle(b)
		if e != nil {
			return fail("profile_too_large")
		}
		engine, e := newExtension(data)
		if e != nil {
			return fail(e.Error())
		}
		engine.Close()
		v, _ := json.Marshal(map[string]any{"bundle": data})
		return v
	}
	return fail("unsupported_command")
}
