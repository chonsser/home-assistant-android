// Copyright (c) Home Assistant contributors
// SPDX-License-Identifier: Apache-2.0

//go:build android

// This file is added to tailcat's cmd/libtailcat package at build time by build-libtailcat.sh
// (go build -overlay), leaving the tailcat sources untouched.
//
// Android denies apps the NETLINK_ROUTE socket since Android 11, so Go's net.Interfaces fails and
// Tailscale's netmon, which every tailcat client starts, fails with it ("netlinkrib: permission
// denied"). The Tailscale Android app registers an interface getter backed by Java; Tailscale's
// androidbin feature does the same for raw binaries, but only in builds without cgo, and libtailcat
// needs cgo. So this registers the same fallback androidbin uses: one synthetic interface carrying
// the source addresses the kernel picks for outbound UDP (dialing UDP sends nothing and is allowed
// in the app sandbox). That is enough to reach the relay (DERP) servers and discover endpoints.

package main

import (
	"net"
	"net/netip"
	"time"

	"tailscale.com/net/netmon"
)

func init() {
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

func androidInterfaces() ([]netmon.Interface, error) {
	// Allowed before Android 11, and the most accurate answer where it works.
	if ifs, err := net.Interfaces(); err == nil {
		ret := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			ret[i].Interface = &ifs[i]
		}
		return ret, nil
	}

	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	// No route at all (offline): report no interfaces rather than failing, so the client starts
	// and connects once the network is back.
	return []netmon.Interface{{
		Interface: &net.Interface{
			Index: 1,
			MTU:   1500,
			Name:  "android",
			Flags: net.FlagUp | net.FlagRunning,
		},
		AltAddrs: addrs,
	}}, nil
}

// outboundIP reports the local source address the kernel picks for an outbound socket to addr.
func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
