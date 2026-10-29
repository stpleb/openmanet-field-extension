# Installs (or updates) the Field Extension on an OpenMANET node from Windows 10/11.
# Right-click > Run with PowerShell. Uses Windows' built-in ssh/scp. You type the node's root
# password (same as the OpenMANET web page) when asked; it is never stored.
Set-Location $PSScriptRoot
Write-Host "Field Extension installer`n=========================="
$ipk = Get-ChildItem -Filter "field-extensions_*_all.ipk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $ipk) { Write-Host "No field-extensions_*.ipk file next to this installer."; Read-Host "Press Enter to close"; exit 1 }
Write-Host "Package: $($ipk.Name)"
$node = Read-Host "Node address [10.41.0.1]"; if (-not $node) { $node = "10.41.0.1" }
if (-not (Test-Connection -ComputerName $node -Count 1 -Quiet)) { Write-Host "Can't reach $node. Join the node's Wi-Fi and try again."; Read-Host "Press Enter to close"; exit 1 }
Write-Host "`nYou will be asked for the node password a few times (Windows ssh can't reuse the sign-in)."
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
ssh -o StrictHostKeyChecking=accept-new "root@$node" 'sysupgrade -b /tmp/pre-fe-install.tar.gz >/dev/null 2>&1 & S=$!; for i in $(seq 1 40); do sleep 1; kill -0 $S 2>/dev/null || break; P=$(pgrep -x flock); [ -n "$P" ] && [ $i -ge 5 ] && kill $P; done; echo backup ready'
scp -O "root@${node}:/tmp/pre-fe-install.tar.gz" ".\backup-$node-$stamp.tar.gz"
scp -O $ipk.FullName "root@${node}:/tmp/fe.ipk"
ssh "root@$node" 'opkg install --force-reinstall /tmp/fe.ipk 2>&1 | tail -12; echo; sh /opt/field-extensions/selftest.sh'
Write-Host "`nDone. Open http://${node}:8787/ (or the ManetNode app) to see the Field Extension."
Read-Host "Press Enter to close"
