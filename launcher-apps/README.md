# ManetNode launcher apps

So nobody has to remember node addresses or ports. Each app lists the node your device's
Wi-Fi is on (found automatically) plus nodes you add, shows each node's status-light colour,
name, battery and mesh size, and opens its **OpenManet** (port 8080) or **FieldExtension**
(port 8787) web page. Same look as the ATAK plugin and the Field Extension web page.

- `android/`  ManetNode for Android: team map with points, geofences and breadcrumbs, team chat,
  push-to-talk, nodes, node settings and the Capabilities Test (range walk test). Build:
  `./gradlew assembleDebug` (JDK 17). The release APK is on the v1.0 release.
- `ios/`      iPhone app (SwiftUI). Free install with your Apple ID; see ios/README.md.
- `desktop/`  Mac desktop launcher (`ManetNode.app`); opens node pages in the browser.
- `windows/`  Windows launcher: double-click `ManetNode.cmd` (PowerShell + Windows Forms, nothing to install). Tested on Windows 11.
