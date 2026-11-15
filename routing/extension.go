// SPDX-License-Identifier: GPL-3.0-or-later
package routing

// The extension compiles ordinary Clash profiles without starting Mihomo's
// listeners, TUN, controller, remote UI or inspection machinery. Resources are
// supplied by the explicitly initiated Android import/update operation.
import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"os"
	"path/filepath"
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
	"github.com/metacubex/mihomo/component/resource"
	"github.com/metacubex/mihomo/tunnel"
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
type extensionEngine struct {
	proxies   map[string]C.Proxy
	rules     []C.Rule
	subRules  map[string][]C.Rule
	providers map[string]P.RuleProvider
	mode      string
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
	// Data files the rules need, wherever a rule sits (top level, AND/OR/NOT, SUB-RULE). Rules inside downloaded classical
	// providers are only known after the download; a missing file then fails the check instead of downloading.
	need := geoNeeds(ruleTexts(raw))
	for _, g := range []struct{ kind, key, def string }{
		{"geoip", "mmdb", "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/country.mmdb"},
		{"geosite", "geosite", "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/geosite.dat"},
		{"asn", "asn", "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/GeoLite2-ASN.mmdb"},
	} {
		if !need[g.kind] {
			continue
		}
		u := g.def
		if urls, ok := raw["geox-url"].(map[string]any); ok {
			if v, ok := urls[g.key].(string); ok && v != "" {
				u = v
			}
		}
		parsed, e := url.Parse(u)
		if e != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil {
			return nil, ErrProfile
		}
		out = append(out, ResourceRequest{g.kind, u, g.kind})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out, nil
}
// ruleTexts returns every rule line of the profile: "rules" and all "sub-rules".
func ruleTexts(raw map[string]any) []string {
	lines, _ := stringsOf(raw["rules"])
	if subs, ok := raw["sub-rules"].(map[string]any); ok {
		for _, v := range subs {
			more, _ := stringsOf(v)
			lines = append(lines, more...)
		}
	}
	return lines
}

// geoNeeds reports which data files rule lines need. It looks for the keywords anywhere in a line, so rules nested in
// AND/OR/NOT payloads count too. GEOIP,LAN needs no database.
func geoNeeds(lines []string) map[string]bool {
	need := map[string]bool{}
	for _, l := range lines {
		u := strings.ToUpper(l)
		if strings.Contains(u, "GEOSITE,") {
			need["geosite"] = true
		}
		if strings.Contains(u, "IP-ASN,") {
			need["asn"] = true
		}
		for _, part := range strings.Split(u, "GEOIP,")[1:] {
			if !strings.HasPrefix(part, "LAN,") && !strings.HasPrefix(part, "LAN)") && part != "LAN" {
				need["geoip"] = true
			}
		}
	}
	return need
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
func newExtension(data []byte, activate bool) (engine *Engine, err error) {
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
		switch r.Kind {
		case "geoip":
			sharedGeo.RLock()
			ready := sharedGeo.reader != nil
			sharedGeo.RUnlock()
			if !ready || !geoHomeSet() {
				return nil, errors.New("geoip_resource_required")
			}
		case "geosite", "asn":
			if !geoReady(r.Kind) {
				return nil, errors.New(r.Kind + "_resource_required")
			}
		default:
			if len(b.Resources[r.Key]) == 0 {
				return nil, errors.New("resources_required")
			}
		}
	}
	x := &extensionEngine{proxies: map[string]C.Proxy{}, mode: b.Mode, subRules: map[string][]C.Rule{}, providers: map[string]P.RuleProvider{}}
	success := false
	defer func() {
		if !success {
			x.close()
		}
	}()
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
	if providers, ok := raw["rule-providers"].(map[string]any); ok {
		for name, v := range providers {
			p, ok := v.(map[string]any)
			if !ok || !label(name) {
				return nil, ErrProfile
			}
			data := b.Resources["rule-providers:"+name]
			if needs := geoNeeds(providerLines(p, data)); needs["geosite"] && !geoReady("geosite") {
				return nil, errors.New("geosite_resource_required")
			} else if needs["asn"] && !geoReady("asn") {
				return nil, errors.New("asn_resource_required")
			} else if needs["geoip"] && !geoHomeSet() {
				return nil, errors.New("geoip_resource_required")
			}
			mapping := map[string]any{"behavior": p["behavior"], "format": p["format"]}
			if p["type"] == "inline" {
				mapping["type"] = "inline"
				mapping["payload"] = p["payload"]
			} else {
				// Mihomo's own provider (yaml, text or mrs) reads the file WAYD downloaded. A file vehicle never fetches.
				path, e := writeProviderFile(name, data)
				if e != nil {
					return nil, e
				}
				mapping["type"] = "file"
				mapping["path"] = path
			}
			rp, e := RP.ParseRuleProvider(name, mapping, R.ParseRule, func(string) resource.BundleFile { return nil })
			if e != nil {
				return nil, errors.New("invalid_rule_provider_entry")
			}
			if e := rp.Initial(); e != nil {
				return nil, errors.New("invalid_rule_provider_entry")
			}
			x.providers[name] = rp
		}
	}
	stage = "rules"
	lines, err := stringsOf(raw["rules"])
	if err != nil || len(lines) == 0 {
		return nil, ErrProfile
	}
	// Sub-rules first, so SUB-RULE lines can refer to them; Mihomo resolves the names when a connection is matched.
	if subs, ok := raw["sub-rules"].(map[string]any); ok {
		for name := range subs {
			x.subRules[name] = nil
		}
		for name, v := range subs {
			entries, e := stringsOf(v)
			if e != nil {
				return nil, e
			}
			for _, line := range entries {
				r, e := x.compile(line)
				if e != nil {
					return nil, e
				}
				x.subRules[name] = append(x.subRules[name], r)
			}
		}
	}
	for _, line := range lines {
		r, e := x.compile(line)
		if e != nil {
			return nil, e
		}
		x.rules = append(x.rules, r)
	}
	adapters := map[string]C.ProxyAdapter{}
	for name, p := range x.proxies {
		adapters[name] = p
	}
	if activate {
		// RULE-SET rules, including ones nested in logic rules, look providers up in Mihomo's registry. Only the engine that
		// routes registers them; validating another profile never replaces the running one's providers.
		tunnel.UpdateRules(x.rules, x.subRules, x.providers)
	}
	success = true
	return &Engine{adapters: adapters, extension: x}, nil
}

// compile parses one rule line with Mihomo's parser, so every Mihomo rule type is accepted. The target must be a known
// proxy or group, or, for SUB-RULE, a sub-rule name. Rules needing a data file compile only after WAYD verified it, so
// Mihomo never starts a download of its own.
func (x *extensionEngine) compile(line string) (C.Rule, error) {
	tp, payload, target, params := RC.ParseRulePayload(line, true)
	if tp == "SUB-RULE" {
		if _, ok := x.subRules[target]; !ok {
			return nil, errors.New("invalid_rule:SUB-RULE")
		}
	} else if x.proxies[target] == nil {
		return nil, errors.New("invalid_rule:" + ruleKeyword(tp))
	}
	if tp == "RULE-SET" {
		if _, ok := x.providers[payload]; !ok {
			return nil, errors.New("invalid_rule:RULE-SET")
		}
	}
	need := geoNeeds([]string{line})
	switch {
	case need["geosite"] && !geoReady("geosite"):
		return nil, errors.New("geosite_resource_required")
	case need["asn"] && !geoReady("asn"):
		return nil, errors.New("asn_resource_required")
	case need["geoip"] && !geoHomeSet():
		return nil, errors.New("geoip_resource_required")
	}
	r, err := R.ParseRule(tp, payload, target, params, x.subRules)
	if err != nil {
		return nil, errors.New("invalid_rule:" + ruleKeyword(tp))
	}
	return r, nil
}

// providerLines returns the rule text of a classical provider (inline payload, or a downloaded yaml/text file) for the
// data-file check. Domain and ipcidr providers, and MRS files, never contain rules that need data files.
func providerLines(p map[string]any, data []byte) []string {
	if stringValue(p["behavior"]) != "classical" {
		return nil
	}
	if p["type"] == "inline" {
		lines, _ := stringsOf(p["payload"])
		return lines
	}
	if stringValue(p["format"]) == "text" {
		return strings.Split(string(data), "\n")
	}
	source, e := decodeYaml(data, 8*1024*1024)
	if e != nil {
		return nil
	}
	lines, _ := stringsOf(source["payload"])
	return lines
}

func geoHomeSet() bool {
	geoHome.RLock()
	defer geoHome.RUnlock()
	return geoHome.dir != ""
}

// writeProviderFile stores a downloaded rule-provider file under the resource folder, named by a hash of the provider name
// so profile text never becomes a path.
func writeProviderFile(name string, data []byte) (string, error) {
	if len(data) == 0 {
		return "", errors.New("resources_required")
	}
	geoHome.RLock()
	dir := geoHome.dir
	geoHome.RUnlock()
	if dir == "" {
		return "", errors.New("invalid_geodata_dir")
	}
	sum := sha256.Sum256([]byte(name))
	path := filepath.Join(dir, "providers", hex.EncodeToString(sum[:12]))
	if os.MkdirAll(filepath.Dir(path), 0o700) != nil || os.WriteFile(path, data, 0o600) != nil {
		return "", ErrProfile
	}
	return path, nil
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
		// As in Clash: the first matching rule decides; nothing matching means DIRECT. SUB-RULE returns the adapter its
		// matching sub-rule chose.
		target = "DIRECT"
		helper := C.RuleMatchHelper{}
		for _, r := range x.rules {
			if ok, adapter := r.Match(meta, helper); ok {
				target = adapter
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
	if v, ok := geodataCommand(request); ok {
		return v
	}
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
		engine, e := newExtension(data, false)
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
	if q.Action == "check" {
		// Compiles the stored profile without activating it, so the screen can say why enabling failed.
		engine, e := newExtension(profile, false)
		if e != nil {
			return fail(e.Error())
		}
		engine.Close()
		return []byte(`{"ok":true}`)
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
		engine, e := newExtension(data, false)
		if e != nil {
			return fail(e.Error())
		}
		engine.Close()
		v, _ := json.Marshal(map[string]any{"bundle": data})
		return v
	}
	return fail("unsupported_command")
}

// ruleKeyword returns tp when it is a plain rule keyword (letters, digits, '-'), so an error never echoes arbitrary profile text.
func ruleKeyword(tp string) string {
	if len(tp) == 0 || len(tp) > 24 {
		return "?"
	}
	for _, c := range tp {
		if !(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-') {
			return "?"
		}
	}
	return tp
}
