//go:build android || linux

// SPDX-License-Identifier: GPL-3.0-or-later
// This private, versioned C ABI owns no listener and accepts no filesystem path.
package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"

	routing "com.nathanhanapps.wayd/routing"
	N "github.com/metacubex/sing/common/network"
)

type config struct {
	engine *routing.Engine
	ctx    context.Context
	cancel context.CancelFunc
}
type flow struct {
	config  uint64
	close   func()
	ctx     context.Context
	dialing bool
}

var registry = struct {
	sync.Mutex
	configs     map[uint64]*config
	flows       map[uint64]flow
	activeDials int
}{configs: map[uint64]*config{}, flows: map[uint64]flow{}}
var sequence atomic.Uint64

//export WaydRouteVersion
func WaydRouteVersion() C.int { return 1 }

//export WaydRouteCreate
func WaydRouteCreate(data unsafe.Pointer, size C.int) (result C.uint64_t) {
	defer func() {
		if recover() != nil {
			result = 0
		}
	}()
	if data == nil || size <= 0 || size > routing.MaxExtensionBytes {
		return 0
	}
	e, err := routing.New(C.GoBytes(data, size))
	if err != nil {
		return 0
	}
	registry.Lock()
	defer registry.Unlock()
	if len(registry.configs) >= 4 {
		e.Close()
		return 0
	}
	ctx, cancel := context.WithCancel(context.Background())
	id := sequence.Add(1)
	registry.configs[id] = &config{e, ctx, cancel}
	return C.uint64_t(id)
}

//export WaydRouteCapabilities
func WaydRouteCapabilities(id C.uint64_t) C.int {
	registry.Lock()
	defer registry.Unlock()
	c := registry.configs[uint64(id)]
	if c == nil {
		return 0
	}
	flags := 1
	if c.engine.NeedsHost() {
		flags |= 2
	}
	if c.engine.NeedsOwner() {
		flags |= 4
	}
	return C.int(flags)
}

//export WaydRouteDestroy
func WaydRouteDestroy(id C.uint64_t) {
	registry.Lock()
	c := registry.configs[uint64(id)]
	delete(registry.configs, uint64(id))
	var closeFns []func()
	for k, f := range registry.flows {
		if f.config == uint64(id) {
			closeFns = append(closeFns, f.close)
			delete(registry.flows, k)
		}
	}
	registry.Unlock()
	if c != nil {
		c.cancel()
		// Adapters are closed after their owned flows below.
	}
	for _, fn := range closeFns {
		fn()
	}
	if c != nil {
		c.engine.Close()
	}
}

//export WaydRouteCommand
func WaydRouteCommand(profile unsafe.Pointer, size C.int, request unsafe.Pointer, requestSize C.int, outSize *C.int) unsafe.Pointer {
	if outSize == nil {
		return nil
	}
	*outSize = 0
	if profile == nil || size <= 0 || size > routing.MaxExtensionBytes || request == nil || requestSize <= 0 || requestSize > routing.MaxExtensionBytes {
		return nil
	}
	result := routing.ExtensionCommand(C.GoBytes(profile, size), C.GoBytes(request, requestSize))
	*outSize = C.int(len(result))
	return C.CBytes(result)
}

//export WaydRouteFree
func WaydRouteFree(data unsafe.Pointer) { C.free(data) }

//export WaydRouteClose
func WaydRouteClose(id C.uint64_t) {
	registry.Lock()
	f, ok := registry.flows[uint64(id)]
	delete(registry.flows, uint64(id))
	registry.Unlock()
	if ok {
		f.close()
	}
}

//export WaydRouteReserve
func WaydRouteReserve(configID C.uint64_t) C.uint64_t {
	registry.Lock()
	defer registry.Unlock()
	c := registry.configs[uint64(configID)]
	if c == nil || len(registry.flows) >= 4096 {
		return 0
	}
	ctx, cancel := context.WithCancel(c.ctx)
	id := sequence.Add(1)
	registry.flows[id] = flow{config: uint64(configID), close: cancel, ctx: ctx}
	return C.uint64_t(id)
}

//export WaydRouteDial
func WaydRouteDial(flowID C.uint64_t, data unsafe.Pointer, size C.int, fd *C.int, outcome *C.int) C.uint64_t {
	if fd == nil || outcome == nil {
		return 0
	}
	*fd = -1
	*outcome = 4
	if data == nil || size <= 0 || size > 1024 {
		return 0
	}
	v := strings.Split(string(C.GoBytes(data, size)), "\n")
	if len(v) != 4 || (v[0] != "tcp" && v[0] != "udp") {
		return 0
	}
	dst, err := netip.ParseAddrPort(v[1])
	if err != nil {
		return 0
	}
	m := routing.Metadata{Destination: dst, Owner: v[2], Host: v[3], UDP: v[0] == "udp"}
	registry.Lock()
	f, exists := registry.flows[uint64(flowID)]
	c := registry.configs[f.config]
	if !exists || f.dialing || c == nil || registry.activeDials >= 64 {
		registry.Unlock()
		return 0
	}
	registry.activeDials++
	f.dialing = true
	registry.flows[uint64(flowID)] = f
	registry.Unlock()
	defer func() { registry.Lock(); registry.activeDials--; registry.Unlock() }()
	r, err := c.engine.Select(m)
	if err != nil {
		return 0
	}
	if r.Kind == "Blocked" {
		*outcome = 3
		return 0
	}
	var remote net.Conn
	var packet net.PacketConn
	if m.UDP {
		packet, _, err = c.engine.OpenUDP(f.ctx, m)
	} else {
		remote, _, err = c.engine.DialTCP(f.ctx, m)
	}
	if err != nil {
		return 0
	}
	var closeOnce sync.Once
	var local net.Conn
	closeAll := func() {
		closeOnce.Do(func() {
			f.close()
			if local != nil {
				local.Close()
			}
			if remote != nil {
				remote.Close()
			}
			if packet != nil {
				packet.Close()
			}
		})
	}
	typeFlag := syscall.SOCK_STREAM
	if m.UDP {
		typeFlag = syscall.SOCK_DGRAM
	}
	pair, err := syscall.Socketpair(syscall.AF_UNIX, typeFlag|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		closeAll()
		return 0
	}
	file := os.NewFile(uintptr(pair[1]), "wayd-route")
	local, err = net.FileConn(file)
	file.Close()
	if err != nil {
		syscall.Close(pair[0])
		closeAll()
		return 0
	}
	id := uint64(flowID)
	registry.Lock()
	if _, ok := registry.flows[id]; !ok || registry.configs[f.config] != c {
		registry.Unlock()
		syscall.Close(pair[0])
		closeAll()
		return 0
	}
	registry.flows[id] = flow{config: f.config, close: closeAll, ctx: f.ctx, dialing: true}
	registry.Unlock()
	finish := func() { WaydRouteClose(C.uint64_t(id)) }
	if m.UDP {
		// Only this flow's fixed numeric destination is accepted or returned.
		dest := net.UDPAddrFromAddrPort(m.Destination)
		go func() {
			defer finish()
			b := make([]byte, 65535)
			for {
				n, e := local.Read(b)
				if e != nil {
					return
				}
				if _, e = packet.WriteTo(b[:n], dest); e != nil {
					return
				}
			}
		}()
		go func() {
			defer finish()
			b := make([]byte, 65535)
			for {
				n, a, e := packet.ReadFrom(b)
				if e != nil {
					return
				}
				peer, ok := a.(*net.UDPAddr)
				if !ok || peer.Port != int(m.Destination.Port()) || peer.AddrPort().Addr().Unmap() != m.Destination.Addr().Unmap() {
					continue
				}
				if _, e = local.Write(b[:n]); e != nil {
					return
				}
			}
		}()
	} else {
		var pumps sync.WaitGroup
		pumps.Add(2)
		go func() {
			defer pumps.Done()
			_, _ = io.Copy(remote, local)
			_ = N.CloseWrite(remote)
		}()
		go func() {
			defer pumps.Done()
			_, _ = io.Copy(local, remote)
			_ = N.CloseWrite(local)
		}()
		go func() { pumps.Wait(); finish() }()
	}
	*fd = C.int(pair[0])
	if r.Kind == "Direct" {
		*outcome = 1
	} else {
		*outcome = 2
	}
	return C.uint64_t(id)
}

func main() {}
