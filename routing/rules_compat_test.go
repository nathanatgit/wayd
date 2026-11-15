// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"encoding/json"
	"net/netip"
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/component/geodata/router"
	"google.golang.org/protobuf/proto"
)

// useGeoHome points the routing layer at a fresh resource folder, as the app does at start.
func useGeoHome(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	var r map[string]any
	if json.Unmarshal(ExtensionCommand(nil, []byte(`{"action":"geodata-home","dir":`+quote(dir)+`}`)), &r) != nil || r["ok"] != true {
		t.Fatal("geodata-home", r)
	}
	return dir
}

func quote(s string) string { v, _ := json.Marshal(s); return string(v) }

func route(t *testing.T, source string, resources map[string][]byte, host string) Route {
	t.Helper()
	b, _ := encodeBundle(ExtensionBundle{Source: []byte(source), Resources: resources, Mode: "rule"})
	engine, e := New(b)
	if e != nil {
		t.Fatal(e)
	}
	defer engine.Close()
	r, e := engine.Select(Metadata{Destination: netip.MustParseAddrPort("192.0.2.1:443"), Host: host})
	if e != nil {
		t.Fatal(e)
	}
	return r
}

func TestSubRulesLogicRulesAndRuleSetsInsideThem(t *testing.T) {
	useGeoHome(t)
	source := `
rule-providers:
  ads: {type: inline, behavior: domain, payload: ['+.ads.example']}
sub-rules:
  web: ['DOMAIN-SUFFIX,blocked.example,REJECT', 'MATCH,DIRECT']
rules:
  - 'AND,((RULE-SET,ads),(DST-PORT,443)),REJECT'
  - 'SUB-RULE,(DOMAIN-KEYWORD,example),web'
  - 'MATCH,DIRECT'
`
	if r := route(t, source, nil, "x.ads.example"); r.Kind != "Blocked" {
		t.Fatal("RULE-SET inside AND", r)
	}
	if r := route(t, source, nil, "a.blocked.example"); r.Kind != "Blocked" {
		t.Fatal("SUB-RULE target", r)
	}
	if r := route(t, source, nil, "fine.example"); r.Kind != "Direct" {
		t.Fatal("sub-rule MATCH", r)
	}
}

func TestNoFinalMatchMeansDirectAsInClash(t *testing.T) {
	useGeoHome(t)
	if r := route(t, "rules: ['DOMAIN,blocked.example,REJECT']", nil, "other.example"); r.Kind != "Direct" {
		t.Fatal(r)
	}
}

func TestTextAndYamlRuleProviderFiles(t *testing.T) {
	useGeoHome(t)
	source := `
rule-providers:
  t: {type: http, behavior: domain, format: text, url: 'https://example.net/t.txt'}
  c: {type: http, behavior: classical, url: 'https://example.net/c.yaml'}
rules: ['RULE-SET,t,REJECT', 'RULE-SET,c,REJECT', 'MATCH,DIRECT']
`
	res := map[string][]byte{
		"rule-providers:t": []byte("# list\n+.text.example\n"),
		// Mihomo's yaml provider reader needs block style (one-line "payload: [...]" files are rejected, as in Clash).
		"rule-providers:c": []byte("payload:\n  - DOMAIN-KEYWORD,classic\n"),
	}
	for host, want := range map[string]string{"a.text.example": "Blocked", "my-classic.net": "Blocked", "free.net": "Direct"} {
		if r := route(t, source, res, host); r.Kind != want {
			t.Fatal(host, r)
		}
	}
}

func TestGeoSiteNeedsAVerifiedFileAndNeverDownloads(t *testing.T) {
	dir := useGeoHome(t)
	source := "rules: ['GEOSITE,cn,REJECT', 'MATCH,DIRECT']"
	var q map[string]any
	_ = json.Unmarshal(ExtensionCommand([]byte(source), []byte(`{"action":"inspect"}`)), &q)
	res := q["resources"].([]any)
	if len(res) != 1 || res[0].(map[string]any)["kind"] != "geosite" {
		t.Fatal("geosite resource request", q["resources"])
	}
	b, _ := encodeBundle(ExtensionBundle{Source: []byte(source), Mode: "rule"})
	if _, e := New(b); e == nil || e.Error() != "geosite_resource_required" {
		t.Fatal("missing geosite accepted", e)
	}
	if _, err := os.Stat(filepath.Join(dir, "GeoSite.dat")); err == nil {
		t.Fatal("a file appeared: something downloaded")
	}
	list := &router.GeoSiteList{Entry: []*router.GeoSite{{CountryCode: "CN", Domain: []*router.Domain{{Type: router.Domain_Domain, Value: "example.cn"}}}}}
	data, _ := proto.Marshal(list)
	if os.WriteFile(filepath.Join(dir, "GeoSite.dat"), data, 0o600) != nil {
		t.Fatal("write")
	}
	var v map[string]any
	_ = json.Unmarshal(ExtensionCommand(nil, []byte(`{"action":"geodata-verify","kind":"geosite"}`)), &v)
	if v["ok"] != true || v["geosite"] != true {
		t.Fatal("verify", v)
	}
	if r := route(t, source, nil, "www.example.cn"); r.Kind != "Blocked" {
		t.Fatal("GEOSITE match", r)
	}
	if r := route(t, source, nil, "example.com"); r.Kind != "Direct" {
		t.Fatal("GEOSITE miss", r)
	}
}

func TestBadGeoFilesAreRejected(t *testing.T) {
	dir := useGeoHome(t)
	_ = os.WriteFile(filepath.Join(dir, "GeoSite.dat"), []byte("not protobuf"), 0o600)
	_ = os.WriteFile(filepath.Join(dir, "ASN.mmdb"), []byte("not mmdb"), 0o600)
	var v map[string]any
	_ = json.Unmarshal(ExtensionCommand(nil, []byte(`{"action":"geodata-home","dir":`+quote(dir)+`}`)), &v)
	if v["geosite"] != false || v["asn"] != false {
		t.Fatal(v)
	}
	b, _ := encodeBundle(ExtensionBundle{Source: []byte("rules: ['IP-ASN,13335,REJECT', 'MATCH,DIRECT']"), Mode: "rule"})
	if _, e := New(b); e == nil || e.Error() != "asn_resource_required" {
		t.Fatal(e)
	}
}
