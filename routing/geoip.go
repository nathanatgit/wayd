// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"encoding/json"
	"github.com/oschwald/maxminddb-golang"
	"net"
	"net/netip"
	"strings"
	"sync"
)

// One immutable database is shared by routing and inspection enrichment. Replaced
// readers stay alive until their last engine reference is released by Go GC.
var sharedGeo struct {
	sync.RWMutex
	reader *maxminddb.Reader
}

func countryCode(reader *maxminddb.Reader, ip netip.Addr) string {
	if reader == nil || !ip.IsValid() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return ""
	}
	var value any
	if reader.Lookup(net.IP(ip.AsSlice()), &value) != nil {
		return ""
	}
	switch v := value.(type) {
	case string:
		return strings.ToUpper(v)
	case []any:
		if len(v) > 0 {
			if s, ok := v[0].(string); ok {
				return strings.ToUpper(s)
			}
		}
	case map[string]any:
		if c, ok := v["country"].(map[string]any); ok {
			return strings.ToUpper(stringValue(c["iso_code"]))
		}
	}
	return ""
}
func geoCommand(request []byte) ([]byte, bool) {
	var q struct {
		Action   string `json:"action"`
		Database []byte `json:"database"`
		IP       string `json:"ip"`
	}
	if json.Unmarshal(request, &q) != nil {
		return nil, false
	}
	switch q.Action {
	case "geoip-load":
		if len(q.Database) == 0 || len(q.Database) > 32*1024*1024 {
			return []byte(`{"error":"invalid_geoip"}`), true
		}
		r, e := maxminddb.FromBytes(q.Database)
		if e != nil {
			return []byte(`{"error":"invalid_geoip"}`), true
		}
		sharedGeo.Lock()
		sharedGeo.reader = r
		sharedGeo.Unlock()
		return []byte(`{"ok":true}`), true
	case "geoip-lookup":
		ip, e := netip.ParseAddr(q.IP)
		if e != nil {
			return []byte(`{"country":""}`), true
		}
		sharedGeo.RLock()
		code := countryCode(sharedGeo.reader, ip)
		sharedGeo.RUnlock()
		// Clash databases also contain routing categories such as CLOUDFLARE.
		// Keep those for GEOIP rules, but never present them as countries in WAYD.
		if len(code) != 2 || code[0] < 'A' || code[0] > 'Z' || code[1] < 'A' || code[1] > 'Z' {
			code = ""
		}
		v, _ := json.Marshal(map[string]string{"country": code})
		return v, true
	}
	return nil, false
}
