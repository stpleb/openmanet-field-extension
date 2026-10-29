# ManetNode for iPhone (free, no App Store, no $99)

A simple node launcher (the full ManetNode app with map, chat and PTT is Android only): lists nodes (10.41.0.1 is always listed; add others with
ADD NODE), shows each node's status-light colour, and opens its OpenManet or FieldExtension
web page inside the app.

## Install on your iPhone with a free Apple ID (one time, about 10 minutes)
1. Plug the iPhone into the Mac with a cable and tap "Trust" on the phone.
2. On the Mac, double-click `ManetNode.xcodeproj` (opens in Xcode).
3. Xcode > Settings > Accounts > "+" > Apple ID, and sign in with your normal Apple ID.
4. Click "ManetNode" in the left column > "Signing & Capabilities" tab > Team: choose
   "(your name) (Personal Team)". If Xcode says the bundle id is taken, change
   `com.manetnode.launcher` to something unique, e.g. `com.stephen.manetnode`.
5. At the top of the window pick your iPhone as the run destination, then press Run (the play button).
6. First time only, on the iPhone: Settings > General > VPN & Device Management > your Apple ID > Trust.
   On iOS 16+ also turn on Settings > Privacy & Security > Developer Mode (the phone restarts).
7. When the app first opens, allow "Local Network" so it can reach the nodes.

## The 7-day limit
Apple lets free Apple ID installs run for 7 days. After that the app won't open until you
plug the phone in and press Run in Xcode again (about a minute; nothing is lost).
SideStore or AltStore can do that refresh over Wi-Fi if you'd rather not plug in weekly.
A paid developer account ($99/year) removes the limit; you don't need it otherwise.
