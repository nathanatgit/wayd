package routing

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestProviderResourceHasSeparateBound(t *testing.T) {
	data := append([]byte("payload: ['+.example.net']\n#"), bytes.Repeat([]byte("x\n#"), 1500000)...)
	if _, e := decodeSource(data); e == nil {
		t.Fatal("source bound bypassed")
	}
	if _, e := decodeYaml(data, 8*1024*1024); e != nil {
		t.Fatal("valid larger provider rejected")
	}
}

func TestSharedGeoIpFeedsRoutingAndLookup(t *testing.T) {
	path := os.Getenv("WAYD_PRIVATE_GEOIP")
	if path == "" {
		t.Skip("optional verified GeoIP fixture")
	}
	data, e := os.ReadFile(path)
	if e != nil {
		t.Fatal("fixture unavailable")
	}
	request, _ := json.Marshal(map[string]any{"action": "geoip-load", "database": data})
	loaded := ExtensionCommand(nil, request)
	if !bytes.Contains(loaded, []byte(`"ok":true`)) {
		t.Fatal("load failed")
	}
	lookup := ExtensionCommand(nil, []byte(`{"action":"geoip-lookup","ip":"223.5.5.5"}`))
	var result struct {
		Country string `json:"country"`
	}
	if json.Unmarshal(lookup, &result) != nil || len(result.Country) != 2 {
		t.Fatal("lookup failed")
	}
	source := []byte("rules: ['GEOIP," + result.Country + ",REJECT', 'MATCH,DIRECT']")
	bundle, _ := encodeBundle(ExtensionBundle{Source: source})
	engine, e := New(bundle)
	if e != nil {
		t.Fatal(e)
	}
	defer engine.Close()
	route, e := engine.Select(Metadata{Destination: netip.MustParseAddrPort("223.5.5.5:443")})
	if e != nil || route.Kind != "Blocked" {
		t.Fatal("routing did not reuse shared GeoIP")
	}
	unknown := ExtensionCommand(nil, []byte(`{"action":"geoip-lookup","ip":"192.168.1.1"}`))
	if string(unknown) != `{"country":""}` {
		t.Fatal("private address received a country")
	}
	bad := ExtensionCommand(nil, []byte(`{"action":"geoip-load","database":"aW52YWxpZA=="}`))
	if !bytes.Contains(bad, []byte("error")) {
		t.Fatal("invalid database accepted")
	}
	if !bytes.Equal(lookup, ExtensionCommand(nil, []byte(`{"action":"geoip-lookup","ip":"223.5.5.5"}`))) {
		t.Fatal("invalid update replaced working database")
	}
}

func TestExtensionCommonProtocolsAndNestedGroups(t *testing.T) {
	source := []byte(`port: 7890
tun: {enable: true}
dns: {enable: true}
proxies:
 - {name: s, type: ss, server: test.example, port: 443, cipher: aes-128-gcm, password: example}
 - {name: v, type: vless, server: test.example, port: 443, uuid: 00000000-0000-0000-0000-000000000001, tls: true}
 - {name: h, type: hysteria2, server: test.example, port: 443, password: example}
proxy-groups:
 - {name: auto, type: url-test, proxies: [s,v,h]}
 - {name: selected, type: select, proxies: [auto, REJECT]}
rules: ['DOMAIN-SUFFIX,example.net,selected','MATCH,REJECT']
`)
	o, e := InspectExtension(source)
	if e != nil || len(o.Proxies) != 3 || len(o.Overrides) != 3 {
		t.Fatal("overview", e)
	}
	b, _ := encodeBundle(ExtensionBundle{Source: source, Selections: map[string]string{"selected": "REJECT"}, Mode: "rule"})
	engine, e := New(b)
	if e != nil {
		t.Fatal(e)
	}
	defer engine.Close()
	route, e := engine.Select(Metadata{Destination: netip.MustParseAddrPort("192.0.2.1:443"), Host: "api.example.net"})
	if e != nil || route.Kind != "Blocked" {
		t.Fatal(route, e)
	}
}
func TestExtensionResourcesRequiredAndInlineRules(t *testing.T) {
	source := []byte("rule-providers:\n  test: {type: http, behavior: domain, url: 'https://example.net/rules.yaml'}\nrules: ['RULE-SET,test,REJECT','MATCH,DIRECT']")
	b, _ := encodeBundle(ExtensionBundle{Source: source})
	if _, e := New(b); e == nil {
		t.Fatal("missing resource accepted")
	}
	var q map[string]any
	_ = json.Unmarshal(ExtensionCommand(source, []byte(`{"action":"inspect"}`)), &q)
	if len(q["resources"].([]any)) != 1 {
		t.Fatal("resource preview")
	}
	b, _ = encodeBundle(ExtensionBundle{Source: source, Resources: map[string][]byte{"rule-providers:test": []byte("payload: ['+.example.net']")}, Mode: "rule"})
	engine, e := New(b)
	if e != nil {
		t.Fatal(e)
	}
	defer engine.Close()
	route, e := engine.Select(Metadata{Destination: netip.MustParseAddrPort("192.0.2.1:443"), Host: "api.example.net"})
	if e != nil || route.Kind != "Blocked" {
		t.Fatal(route, e)
	}
}
func TestExtensionBadResourcesAndCyclesFailClosed(t *testing.T) {
	for _, source := range []string{"rule-providers: {a: {type: http, url: 'file:///private'}}\nrules: ['MATCH,DIRECT']", "proxy-groups: [{name: a, type: select, proxies: [a]}]\nrules: ['MATCH,a']"} {
		b, _ := encodeBundle(ExtensionBundle{Source: []byte(source)})
		if _, e := New(b); e == nil {
			t.Fatal("unsafe profile accepted")
		}
	}
}
func TestPrivateDownloadedProfile(t *testing.T) {
	path := os.Getenv("WAYD_PRIVATE_PROFILE")
	if path == "" {
		t.Skip("optional local credential-bearing fixture")
	}
	data, e := os.ReadFile(path)
	if e != nil {
		t.Fatal("fixture unavailable")
	}
	o, e := InspectExtension(data)
	if e != nil {
		t.Fatal("inspection failed", e)
	}
	t.Logf("profile counts: %d proxies, %d groups, %d rules, %d resources", len(o.Proxies), len(o.Groups), len(o.Rules), len(o.Resources))
}

// Explicitly requested private fixtures for Android live network integration.
// Never print node names, addresses, credentials or profile bytes.
func TestExportPrivateLiveNodeFixtures(t *testing.T) {
	dir := os.Getenv("WAYD_PRIVATE_LIVE_NODE_DIR")
	if dir == "" {
		t.Skip("optional private live network fixture export")
	}
	data, e := os.ReadFile(os.Getenv("WAYD_PRIVATE_PROFILE"))
	if e != nil {
		t.Fatal("fixture unavailable")
	}
	raw, e := decodeSource(data)
	if e != nil {
		t.Fatal("fixture invalid")
	}
	nodes, e := objects(raw["proxies"])
	if e != nil {
		t.Fatal("nodes invalid")
	}
	if os.MkdirAll(dir, 0700) != nil {
		t.Fatal("fixture directory")
	}
	for index, node := range nodes {
		source, _ := json.Marshal(map[string]any{"proxies": []any{node}, "rules": []string{"MATCH," + stringValue(node["name"])}})
		bundle, e := encodeBundle(ExtensionBundle{Source: source, Mode: "rule"})
		if e != nil {
			t.Fatal("fixture encode")
		}
		engine, e := New(bundle)
		if e != nil {
			t.Fatal("fixture compile")
		}
		engine.Close()
		if os.WriteFile(filepath.Join(dir, fmt.Sprintf("node-%02d.bundle", index+1)), bundle, 0600) != nil {
			t.Fatal("fixture write")
		}
	}
	t.Logf("prepared %d private node fixtures", len(nodes))
}

func TestPrivateProfileWithDownloadedResources(t *testing.T) {
	if os.Getenv("WAYD_PRIVATE_DOWNLOADS") != "1" {
		t.Skip("explicit resource download fixture")
	}
	data, e := os.ReadFile(os.Getenv("WAYD_PRIVATE_PROFILE"))
	if e != nil {
		t.Fatal("fixture unavailable")
	}
	o, e := InspectExtension(data)
	if e != nil {
		t.Fatal("inspection", e)
	}
	resources := map[string][]byte{}
	client := &http.Client{Timeout: 90 * time.Second}
	for _, r := range o.Resources {
		if r.Key == "geoip" && os.Getenv("WAYD_PRIVATE_GEOIP") != "" {
			v, e := os.ReadFile(os.Getenv("WAYD_PRIVATE_GEOIP"))
			if e != nil {
				t.Fatal("geoip fixture")
			}
			resources[r.Key] = v
			continue
		}
		cacheDir := os.Getenv("WAYD_PRIVATE_RESOURCE_DIR")
		cachePath := filepath.Join(cacheDir, fmt.Sprintf("%x", sha256.Sum256([]byte(r.URL))))
		if cacheDir != "" {
			if saved, e := os.ReadFile(cachePath); e == nil {
				resources[r.Key] = saved
				continue
			}
		}
		response, e := client.Get(r.URL)
		if e != nil {
			t.Fatal("resource request failed", r.Key)
		}
		if response.StatusCode != 200 {
			response.Body.Close()
			t.Fatal("resource status", r.Key, response.StatusCode)
		}
		content, e := io.ReadAll(io.LimitReader(response.Body, 32*1024*1024+1))
		response.Body.Close()
		if e != nil || len(content) > 32*1024*1024 {
			t.Fatal("resource size", r.Key)
		}
		resources[r.Key] = content
		if cacheDir != "" {
			_ = os.MkdirAll(cacheDir, 0700)
			_ = os.WriteFile(cachePath, content, 0600)
		}
	}
	bundle, e := encodeBundle(ExtensionBundle{Source: data, Resources: resources, Mode: "rule"})
	if e != nil {
		t.Fatal("bundle", e)
	}
	engine, e := New(bundle)
	if e != nil {
		t.Fatal("compile", e)
	}
	defer engine.Close()
	if out := os.Getenv("WAYD_PRIVATE_BUNDLE"); out != "" {
		if os.WriteFile(out, bundle, 0600) != nil {
			t.Fatal("bundle write")
		}
	}
	t.Logf("compiled user profile: %d proxy nodes, %d groups, %d downloaded resources, %d compiled rules", len(o.Proxies), len(o.Groups), len(resources), len(engine.extension.rules))
}
