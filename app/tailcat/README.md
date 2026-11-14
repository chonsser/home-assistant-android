# Tailcat

[Tailcat](https://github.com/tailscale/tailcat) gives a Home Assistant server an end-to-end
encrypted WireGuard tunnel, relayed over Tailscale's public DERP servers, without an account,
a tailnet or port forwarding. The Tailcat integration of Home Assistant pairs a device by showing
a link (also as a QR code):

    homeassistant://tailcat/pair?address=<tc…>&key=<privkey:…>&port=<tunnel port>&name=<instance name>

The server generates the device's key pair; the link carries the device's private key and the
server's Tailcat address. Both are secrets: never log them.

## Native library

The app embeds libtailcat, the C API of Tailcat (`cmd/libtailcat`, ABI version 1), and calls it
through a small JNI bridge (`tailcat_jni.c`). Build both for every ABI the app ships with:

```sh
app/tailcat/build-libtailcat.sh
```

It needs the Android NDK (r25 or newer, `ANDROID_NDK_HOME` or the newest one in
`$ANDROID_HOME/ndk`) and the Go toolchain required by Tailcat's `go.mod`, and writes
`libtailcat.so` and `libtailcat_jni.so` to `app/tailcat/jniLibs/<abi>/` (git-ignored, about
16 MB per ABI). The `:app` and `:automotive` modules package that directory. `TAILCAT_SRC` builds
an existing checkout instead of cloning the pinned commit; `TAILCAT_ABIS` limits the ABIs.

Without the libraries the app builds and runs normally: `LibTailcat.isAvailable` is `false`, no
server gets a tunnel URL, and opening a pairing link says Tailcat is not available in this build.

`android_interfaces.go` is compiled into libtailcat with `go build -overlay`, leaving the Tailcat
sources untouched. Android denies apps the netlink socket Go needs to list network interfaces, so
without it every client fails to start (`netmon.New: … netlinkrib: permission denied`).

## How the app uses it

- `TailcatTunnelManager` runs one `TailcatLoopbackProxy` per tunnel endpoint (server address and
  port): every connection to `http://127.0.0.1:<port>` is piped to the server's port inside the
  tunnel. The port is derived from the endpoint (FNV-1a, as in the iOS app) so the WebView keeps
  its storage for that origin across launches.
- The loopback URL is never stored. A server keeps its Tailcat configuration, encrypted with an
  Android Keystore key (`KeystoreTailcatConfigCipher`), in the `tailcat` column of its connection
  info; `TailcatUrlProvider` resolves the loopback URL when the server's URL is needed.
- URL priority: internal (on the home network) > cloud (when enabled) > HTTPS external > Tailcat >
  plain HTTP external. The tunnel counts as a secure connection.

## Limitations

- Only processes of the phone app (`:app`, `:automotive`) run tunnels. Widgets, tiles and
  background work resolve the tunnel like the frontend does, so they use it while the app process
  can reach the network. Wear OS has no tunnel: a server reachable only through Tailcat gives the
  watch no URL, and Wear onboarding from the phone does not offer it.
- The loopback interface is shared by all apps on a device: another app could reach Home
  Assistant's unauthenticated pages (the login page) through the proxy port. Everything else still
  requires Home Assistant credentials.
- Restoring a backup on another device keeps the server but not its pairing (the Keystore key does
  not move); pair again with a new pairing.
