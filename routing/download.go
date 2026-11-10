// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"bytes"
	"context"
	"errors"
	C "github.com/metacubex/mihomo/constant"
	"go.yaml.in/yaml/v3"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

var resourceDownloads = struct {
	sync.Mutex
	active map[string]context.CancelFunc
}{active: make(map[string]context.CancelFunc)}

func cancelResourceDownload(id string) {
	resourceDownloads.Lock()
	defer resourceDownloads.Unlock()
	if cancel := resourceDownloads.active[id]; cancel != nil {
		cancel()
	}
}

// A user-requested resource fetch can use an embedded node before rule providers
// exist. This bootstrap engine has no traffic rules, listeners or resources.
func downloadResource(source []byte, address string, viaProxy bool, limit int) ([]byte, error) {
	return downloadResourceWithID(source, address, viaProxy, limit, "")
}

func downloadResourceWithID(source []byte, address string, viaProxy bool, limit int, id string) ([]byte, error) {
	if limit <= 0 || limit > 32*1024*1024 {
		return nil, ErrProfile
	}
	target, e := url.Parse(address)
	if e != nil || target.Scheme != "https" || target.Hostname() == "" || target.User != nil {
		return nil, ErrProfile
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if id != "" {
		resourceDownloads.Lock()
		if len(id) > 64 || len(resourceDownloads.active) >= 8 || resourceDownloads.active[id] != nil {
			resourceDownloads.Unlock()
			return nil, ErrProfile
		}
		resourceDownloads.active[id] = cancel
		resourceDownloads.Unlock()
		defer func() { resourceDownloads.Lock(); delete(resourceDownloads.active, id); resourceDownloads.Unlock() }()
	}
	transport := &http.Transport{Proxy: nil, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 20 * time.Second}
	defer transport.CloseIdleConnections()
	var engine *Engine
	if viaProxy {
		b, e := decodeBundle(source)
		if e != nil {
			return nil, e
		}
		raw, e := decodeSource(b.Source)
		if e != nil {
			return nil, e
		}
		proxies, e := objects(raw["proxies"])
		if e != nil || len(proxies) == 0 {
			return nil, errors.New("bootstrap_node_required")
		}
		// Use an actual declared node, never an implicit Direct/group fallback.
		selected := stringValue(proxies[0]["name"])
		minimal := map[string]any{"proxies": []any{proxies[0]}, "rules": []string{"MATCH," + selected}}
		data, e := yaml.Marshal(minimal)
		if e != nil {
			return nil, ErrProfile
		}
		bundle, e := encodeBundle(ExtensionBundle{Source: data, Mode: "rule"})
		if e != nil {
			return nil, e
		}
		engine, e = newExtension(bundle)
		if e != nil {
			return nil, e
		}
		defer engine.Close()
		a := engine.adapters[selected]
		transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, e := net.SplitHostPort(address)
			if e != nil {
				return nil, e
			}
			p, e := strconv.ParseUint(port, 10, 16)
			if e != nil {
				return nil, e
			}
			meta := &C.Metadata{NetWork: C.TCP, DstPort: uint16(p), Host: host}
			if ip, e := netip.ParseAddr(host); e == nil {
				meta.DstIP = ip
				meta.Host = ""
			}
			return a.DialContext(ctx, meta)
		}
	}
	client := &http.Client{Transport: transport, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) > 5 || req.URL.Scheme != "https" || req.URL.User != nil {
			return ErrProfile
		}
		return nil
	}}
	request, e := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if e != nil {
		return nil, ErrProfile
	}
	request.Header.Set("User-Agent", "WAYD-Clash/1")
	response, e := client.Do(request)
	if e != nil {
		return nil, errors.New("resource_download_failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK || response.ContentLength > int64(limit) {
		return nil, errors.New("resource_response_rejected")
	}
	data, e := io.ReadAll(io.LimitReader(response.Body, int64(limit)+1))
	if e != nil || len(data) > limit || len(bytes.TrimSpace(data)) == 0 {
		return nil, errors.New("resource_download_failed")
	}
	if strings.HasPrefix(string(data[:min(len(data), 64)]), "<!DOCTYPE html") {
		return nil, errors.New("resource_response_rejected")
	}
	return data, nil
}
