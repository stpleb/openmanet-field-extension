# Field Extension node installer

Installs or updates the Field Extension on an OpenMANET node (OpenMANET 1.8.0) from a computer.
For a brand-new node the SD card image is easier (see the main README).

1. Join the node's Wi-Fi (or plug into its LAN port).
2. Mac: double-click **Install Field Extension (Mac).command** (first time: right-click > Open).
   Windows 10/11: right-click **Install Field Extension (Windows).ps1** > Run with PowerShell.
3. Press Return for the default address 10.41.0.1, or type the node's address.
4. Type the node's root password when asked (the OpenMANET web page password).

It backs up the node's settings into this folder first, installs the newest
`field-extensions_*_all.ipk` in this folder, then runs the self-test and prints PASS/FAIL for
each check.

The package needs Python (`python3-light` and a few modules). Stock OpenMANET 1.8 has none, so
on a stock node opkg fetches them from the OpenWrt package feed: the node needs internet for the
first install (plug its LAN port into a router). Nodes made from the SD card image already have it.

To install a newer build, drop its .ipk into this folder; the installer picks the newest one.
