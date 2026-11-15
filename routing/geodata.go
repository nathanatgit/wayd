// SPDX-License-Identifier: GPL-3.0-or-later
package routing

// Mihomo's GEOIP, GEOSITE and ASN rules read data files from its home directory and download them by themselves when a
// file is missing or invalid. WAYD points the home directory at its own resource folder, clears every download URL so a
// missing file fails instead of downloading, and only compiles rules that need a file after WAYD verified it (D-075).
import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"

	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/geodata/router"
	"github.com/metacubex/mihomo/component/mmdb"
	C "github.com/metacubex/mihomo/constant"
	RP "github.com/metacubex/mihomo/rules/provider"
	"github.com/metacubex/mihomo/tunnel"
	"google.golang.org/protobuf/proto"
)

var geoHome struct {
	sync.RWMutex
	dir      string
	verified map[string]bool // "geosite", "asn", "geoip"
}

const maxGeoBytes = 64 * 1024 * 1024

func init() {
	// No implicit downloads, ever: an empty URL makes Mihomo's downloader fail without touching the network.
	geodata.SetGeoSiteUrl("")
	geodata.SetASNUrl("")
	geodata.SetMmdbUrl("")
	geodata.SetGeoIpUrl("")
	geodata.SetGeodataMode(false)
	// RULE-SET rules find providers through this registry, which Mihomo's own config loader would set.
	RP.SetTunnel(tunnel.Tunnel)
}

func geoFileName(kind string) string {
	switch kind {
	case "geosite":
		return C.GeositeName
	case "asn":
		return C.ASNName
	}
	return ""
}

// geoReady reports whether a data file of this kind was verified in the current home directory.
func geoReady(kind string) bool {
	geoHome.RLock()
	defer geoHome.RUnlock()
	return geoHome.dir != "" && geoHome.verified[kind]
}

func verifyGeoFile(kind, path string) bool {
	st, err := os.Stat(path)
	if err != nil || st.IsDir() || st.Size() == 0 || st.Size() > maxGeoBytes {
		return false
	}
	switch kind {
	case "geosite":
		data, err := os.ReadFile(path)
		if err != nil {
			return false
		}
		var list router.GeoSiteList
		return proto.Unmarshal(data, &list) == nil && len(list.Entry) > 0
	case "asn":
		return mmdb.Verify(path)
	}
	return false
}

// geodataCommand handles `geodata-home` (set the resource folder once per process start, verifying what is there) and
// `geodata-verify` (after WAYD replaced one file). Unknown actions fall through to the other commands.
func geodataCommand(request []byte) ([]byte, bool) {
	var q struct {
		Action string `json:"action"`
		Dir    string `json:"dir"`
		Kind   string `json:"kind"`
	}
	if json.Unmarshal(request, &q) != nil || (q.Action != "geodata-home" && q.Action != "geodata-verify") {
		return nil, false
	}
	geoHome.Lock()
	defer geoHome.Unlock()
	// Read while the lock is held; geoReady would take it again.
	result := func(ok bool) []byte {
		v, _ := json.Marshal(map[string]any{"ok": ok, "geosite": geoHome.verified["geosite"], "asn": geoHome.verified["asn"]})
		return v
	}
	switch q.Action {
	case "geodata-home":
		st, err := os.Stat(q.Dir)
		if !filepath.IsAbs(q.Dir) || err != nil || !st.IsDir() {
			return []byte(`{"error":"invalid_geodata_dir"}`), true
		}
		if geoHome.dir != q.Dir {
			C.SetHomeDir(q.Dir)
			geoHome.dir = q.Dir
		}
		geoHome.verified = map[string]bool{}
		for _, kind := range []string{"geosite", "asn"} {
			geoHome.verified[kind] = verifyGeoFile(kind, filepath.Join(q.Dir, geoFileName(kind)))
		}
		mmdb.ReloadIP()
		mmdb.ReloadASN()
		return result(true), true
	default: // geodata-verify
		if geoHome.dir == "" {
			return []byte(`{"error":"invalid_geodata_dir"}`), true
		}
		if q.Kind == "geoip" {
			mmdb.ReloadIP()
			return result(true), true
		}
		name := geoFileName(q.Kind)
		if name == "" {
			return []byte(`{"error":"invalid_geodata_kind"}`), true
		}
		ok := verifyGeoFile(q.Kind, filepath.Join(geoHome.dir, name))
		geoHome.verified[q.Kind] = ok
		if q.Kind == "asn" {
			mmdb.ReloadASN()
		}
		return result(ok), true
	}
}
