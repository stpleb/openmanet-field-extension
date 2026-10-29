# ManetNode launcher (Windows)

Double-click `ManetNode.cmd` (keep `ManetNode.ps1` next to it). It lists 10.41.0.1, the other
nodes that node sees on the mesh, and any nodes you add. Each card shows the node's status-light
colour, name, battery and mesh size, with buttons that open its OpenManet (8080) or
FieldExtension (8787) page in your browser. It refreshes every 30 s while the window is in front.

Needs nothing extra: Windows 10/11 PowerShell and Windows Forms are built in. Added nodes are kept
in `%APPDATA%\ManetNode\nodes.txt`.

If Windows SmartScreen warns about the file the first time, choose More info > Run anyway.
Tested on Windows 11.
