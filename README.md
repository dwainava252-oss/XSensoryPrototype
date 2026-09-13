# XSensory

An Android app for sharing media files directly between two phones over
**Wi-Fi Direct** — no internet connection, no server, no cloud upload. Two
devices discover each other, connect, and transfer files peer-to-peer over a
local socket connection.

## Why

Most "share a file" flows either need both devices online, or route the
file through a third-party server/cloud bucket. XSensory skips both — as
long as two phones are near each other and can find one another over Wi-Fi
Direct, they can move files directly, at local network speed.

## How it works

**1. Discovery & connection (`WifiDirectManager`, `WifiDirectReceiver`)**
Wraps Android's `WifiP2pManager` to discover nearby peers and connect to one.
Once connected, the pair forms a Wi-Fi Direct group — one device becomes the
**group owner** (acts like a small access point) and both devices get an IP
on the local group network.

**2. Role negotiation (`RoleHandshake`)**
Connecting two phones doesn't automatically tell you which one is sending
and which is receiving. Both sides open a small coordination socket
(port `8889`) and exchange a single byte — `'S'` or `'R'`. If both sides
happen to pick the same role, that's a conflict, and it's renegotiated.

**3. File transfer (`FileSenderService`, `FileReceiverService`)**
Once roles are settled:
- The **sender** opens a `Socket` to port `8888`, writes a small text header
  (`XFER:filename:size:mimetype\n`) so the receiver knows what's coming,
  then streams the file bytes.
- The **receiver** listens with a `ServerSocket`, parses that header, and
  writes the incoming bytes to a `MediaStore`-registered file so it shows up
  normally in the phone's gallery/files app.

All socket I/O runs on background threads — Android throws
`NetworkOnMainThreadException` if you try this on the UI thread — with
progress reported back via `TransferProgressCallback`.

## A few real problems this handles

- **Receiver not ready yet.** The sender retries connecting for up to 30
  seconds rather than failing immediately, since the receiving side might
  not have tapped "Receive" yet.
- **Stale sockets.** If "Receive" is tapped twice, an old `ServerSocket` can
  still be bound to the port. The receiver explicitly closes any previous
  socket before rebinding to avoid `EADDRINUSE`.
- **Role conflicts.** Covered above — both devices agreeing on sender vs.
  receiver before any file bytes move.

## Stack

- Kotlin, Android SDK
- Wi-Fi Direct (`WifiP2pManager`)
- Raw TCP sockets (`Socket` / `ServerSocket`) for the transfer protocol
- `MediaStore` for saving received files

## Known limitations / next steps

- No resume support — if the connection drops mid-transfer, the transfer
  fails rather than continuing from where it left off.
- Custom text header protocol is simple by design; a production version
  could move to something more extensible (JSON/Protobuf).
- Early prototyping explored Google's Nearby Connections API before settling
  on raw Wi-Fi Direct sockets for more control over the transfer protocol —
  some leftover references may still exist in helper files.

## Setup

```bash
git clone <repo-url>
cd XSensoryPrototype
./gradlew installDebug
```

Requires two Android devices with Wi-Fi Direct support (most phones from the
last decade). Grant location and nearby-devices permissions when prompted —
Android requires these for Wi-Fi Direct peer discovery.

## Installing the app on a device

Since this is a peer-to-peer app, you'll need **two physical Android
phones** to actually test it — an emulator won't work, since emulators
don't support real Wi-Fi Direct hardware.

**1. Enable Developer Options on the phone**
Settings → About Phone → tap "Build Number" 7 times.

**2. Enable USB Debugging**
Settings → Developer Options → toggle "USB Debugging" on.

**3. Connect the phone to your computer via USB**
A popup will appear on the phone asking "Allow USB debugging?" — tap Allow.

**4. Verify the device is detected**
```bash
adb devices
```
You should see your device listed. (Requires Android SDK platform-tools,
which comes bundled with Android Studio.)

**5. Build and install**
```bash
./gradlew installDebug
```
This builds the APK and installs it directly on the connected phone — it'll
appear in the app drawer as "XSensory."

Repeat this for a second phone (or share the built APK to it directly), so
you have two devices to actually connect to each other.

**Prefer a GUI over the command line?** Open the project in Android Studio,
connect the phone via USB (steps 1–3 above still apply), select the device
from the dropdown at the top, and click the green ▶ Run button.
