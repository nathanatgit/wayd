package routing

import (
	"net"
	"testing"
	"time"
)

func TestCancelDownloadInterruptsStalledTLS(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, e := listener.Accept()
		if e == nil {
			accepted <- c
		}
	}()
	done := make(chan error, 1)
	go func() {
		_, e := downloadResourceWithID(nil, "https://"+listener.Addr().String(), false, 1024, "cancel-fixture")
		done <- e
	}()
	select {
	case c := <-accepted:
		defer c.Close()
	case <-time.After(3 * time.Second):
		t.Fatal("download did not connect")
	}
	// Cancel must not require a valid profile or wait on the active download.
	if string(ExtensionCommand(nil, []byte(`{"action":"cancel-download","id":"cancel-fixture"}`))) != `{"ok":true}` {
		t.Fatal("cancel rejected")
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("stalled TLS succeeded")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cancel did not interrupt network I/O")
	}
	resourceDownloads.Lock()
	defer resourceDownloads.Unlock()
	if resourceDownloads.active["cancel-fixture"] != nil {
		t.Fatal("download registration leaked")
	}
}
