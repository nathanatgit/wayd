// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"bytes"
	"crypto/x509"
	"fmt"
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
	// A large GeoIP database through a slow node can legitimately take minutes, so the wall-clock cap is generous and a
	// stall watchdog (no bytes for downloadStall) ends dead transfers early instead.
	ctx, cancel := context.WithTimeout(context.Background(), downloadDeadline)
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
	direct := &net.Dialer{Timeout: 15 * time.Second}
	transport := &http.Transport{Proxy: nil, DialContext: direct.DialContext, TLSHandshakeTimeout: 15 * time.Second, ResponseHeaderTimeout: 30 * time.Second}
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
			c, e := a.DialContext(ctx, meta)
			if e != nil {
				return nil, proxyDialError{e}
			}
			return c, nil
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
		return nil, errors.New(classifyDownloadError(ctx, e))
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("resource_http_%d", response.StatusCode)
	}
	if response.ContentLength > int64(limit) {
		return nil, errors.New("resource_too_large")
	}
	watch := newStallWatch(cancel, downloadStall)
	defer watch.stop()
	data, e := io.ReadAll(io.LimitReader(watch.reader(response.Body), int64(limit)+1))
	if e != nil {
		if watch.fired() {
			return nil, errors.New("resource_stalled")
		}
		return nil, errors.New(classifyDownloadError(ctx, e))
	}
	if len(data) > limit {
		return nil, errors.New("resource_too_large")
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil, errors.New("resource_empty")
	}
	if strings.HasPrefix(string(data[:min(len(data), 64)]), "<!DOCTYPE html") {
		return nil, errors.New("resource_html")
	}
	return data, nil
}

const (
	downloadDeadline = 4 * time.Minute
	downloadStall    = 25 * time.Second
)

// proxyDialError marks a failure to reach the destination through the selected node.
type proxyDialError struct{ err error }

func (e proxyDialError) Error() string { return e.err.Error() }
func (e proxyDialError) Unwrap() error { return e.err }

// classifyDownloadError maps a transport failure to a stable, URL-free code the UI can explain.
func classifyDownloadError(ctx context.Context, e error) string {
	var dns *net.DNSError
	var proxy proxyDialError
	var unknownAuthority x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	switch {
	case errors.Is(ctx.Err(), context.Canceled):
		return "resource_canceled"
	case errors.Is(ctx.Err(), context.DeadlineExceeded), errors.Is(e, context.DeadlineExceeded):
		return "resource_timeout"
	case errors.As(e, &proxy):
		return "resource_proxy"
	case errors.As(e, &dns):
		return "resource_dns"
	case errors.As(e, &unknownAuthority), errors.As(e, &hostname), errors.As(e, &invalid),
		strings.Contains(e.Error(), "tls:"), strings.Contains(e.Error(), "TLS handshake"):
		return "resource_tls"
	}
	var op *net.OpError
	if errors.As(e, &op) && op.Op == "dial" {
		return "resource_connect"
	}
	var timeout interface{ Timeout() bool }
	if errors.As(e, &timeout) && timeout.Timeout() {
		return "resource_timeout"
	}
	return "resource_network"
}

// stallWatch cancels a transfer that delivers no bytes for the given period.
type stallWatch struct {
	timer *time.Timer
	idle  time.Duration
	mu    sync.Mutex
	hit   bool
}

func newStallWatch(cancel context.CancelFunc, idle time.Duration) *stallWatch {
	w := &stallWatch{idle: idle}
	w.timer = time.AfterFunc(idle, func() {
		w.mu.Lock()
		w.hit = true
		w.mu.Unlock()
		cancel()
	})
	return w
}

func (w *stallWatch) stop() { w.timer.Stop() }
func (w *stallWatch) fired() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.hit
}
func (w *stallWatch) reader(r io.Reader) io.Reader { return stallReader{r, w} }

type stallReader struct {
	r io.Reader
	w *stallWatch
}

func (s stallReader) Read(p []byte) (int, error) {
	n, e := s.r.Read(p)
	if n > 0 {
		s.w.timer.Reset(s.w.idle)
	}
	return n, e
}
