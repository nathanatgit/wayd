// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"context"
	"errors"
	"github.com/dlclark/regexp2"
	C "github.com/metacubex/mihomo/constant"
	"net/url"
	"strings"
	"time"
)

// Seed automatic groups with user-requested probe results across engine restarts.
// Probes are explicit; importing/validating a profile never generates a probe.
type measuredProxy struct {
	C.Proxy
	delay int
}

func (p *measuredProxy) AliveForTestUrl(string) bool { return p.delay >= 0 }
func (p *measuredProxy) LastDelayForTestUrl(string) uint16 {
	if p.delay < 0 {
		return 65535
	}
	if p.delay > 65534 {
		return 65534
	}
	return uint16(p.delay)
}
func filterMembers(group map[string]any, members []string, proxies map[string]C.Proxy) ([]string, error) {
	compile := func(key string) ([]*regexp2.Regexp, error) {
		s := stringValue(group[key])
		if len(s) > 2048 {
			return nil, ErrProfile
		}
		out := []*regexp2.Regexp{}
		if s == "" {
			return out, nil
		}
		for _, part := range strings.Split(s, "`") {
			r, e := regexp2.Compile(part, regexp2.None)
			if e != nil {
				return nil, ErrProfile
			}
			r.MatchTimeout = 20 * time.Millisecond
			out = append(out, r)
		}
		return out, nil
	}
	include, e := compile("filter")
	if e != nil {
		return nil, e
	}
	exclude, e := compile("exclude-filter")
	if e != nil {
		return nil, e
	}
	out := []string{}
	seen := map[string]bool{}
	for _, name := range members {
		if seen[name] {
			continue
		}
		seen[name] = true
		allowed := len(include) == 0
		for _, r := range include {
			ok, e := r.MatchString(name)
			if e != nil {
				return nil, ErrProfile
			}
			allowed = allowed || ok
		}
		for _, r := range exclude {
			ok, e := r.MatchString(name)
			if e != nil {
				return nil, ErrProfile
			}
			if ok {
				allowed = false
			}
		}
		if allowed {
			out = append(out, name)
		}
	}
	return out, nil
}
func testNodes(engine *Engine, address string) (map[string]int, error) {
	u, e := url.Parse(address)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil {
		return nil, errors.New("https_test_url_required")
	}
	out := map[string]int{}
	// Bounded sequential tests do not open an uncontrolled health-check fanout.
	count := 0
	for name, p := range engine.extension.proxies {
		switch p.Type() {
		case C.Direct, C.Reject, C.RejectDrop, C.Selector, C.URLTest, C.Fallback, C.LoadBalance:
			continue
		}
		count++
		if count > 256 {
			return nil, ErrProfile
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		delay, e := p.URLTest(ctx, address, nil)
		cancel()
		if e != nil {
			out[name] = -1
		} else {
			out[name] = int(delay)
		}
	}
	return out, nil
}
