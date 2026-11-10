// SPDX-License-Identifier: GPL-3.0-or-later
package routing

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestDownloadReportsTlsFailure(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("x")) }))
	defer server.Close()
	_, e := downloadResource(nil, server.URL+"/rules.yaml", false, 1024)
	if e == nil || e.Error() != "resource_tls" {
		t.Fatalf("want resource_tls, got %v", e)
	}
}

func TestStallWatchCancelsIdleTransfer(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	w := newStallWatch(cancel, 50*time.Millisecond)
	defer w.stop()
	r, pw := io.Pipe()
	go func() { _, _ = pw.Write([]byte("a")); <-ctx.Done(); _ = pw.CloseWithError(ctx.Err()) }()
	_, e := io.ReadAll(w.reader(r))
	if e == nil || !w.fired() {
		t.Fatalf("watchdog did not fire: %v", e)
	}
}
