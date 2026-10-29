# ManetNode launcher (Windows). Lists OpenMANET nodes, shows each node's status-light colour,
# name, battery and mesh size, and opens its OpenManet (8080) or FieldExtension (8787) page in
# the browser. Same node list idea and look as the Mac, iPhone and Android launchers.
# Plain PowerShell 5.1 + Windows Forms (built into Windows 10/11); nothing to install.

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$FePort = 8787; $OmPort = 8080; $DefaultNode = '10.41.0.1'
$Store = Join-Path $env:APPDATA 'ManetNode'
$SavedFile = Join-Path $Store 'nodes.txt'
New-Item -ItemType Directory -Force -Path $Store | Out-Null

function C([string]$hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }
$BG = C '#05080A'; $PANEL = C '#0A0F13'; $LINE = C '#0E5560'; $TEXT = C '#D9E6ED'
$SUB = C '#8FA7B2'; $MUTED = C '#5C7682'; $CYAN = C '#00E5FF'; $OK = C '#00E676'; $BAD = C '#FF3B4D'
$LED = @{ magenta = (C '#B56CFF'); green = $OK; cyan = $CYAN; red = $BAD; amber = (C '#FFB300');
          blue = (C '#4D8BFF'); white = [System.Drawing.Color]::White }

function Get-Saved { if (Test-Path $SavedFile) { @(Get-Content $SavedFile | Where-Object { $_.Trim() }) } else { @() } }
function Set-Saved($list) { Set-Content -Path $SavedFile -Value $list }

function Get-Json([string]$hostName, [string]$name) {
    try { Invoke-RestMethod -Uri "http://${hostName}:$FePort/api/field/$name" -TimeoutSec 3 -UseBasicParsing }
    catch { $null }
}

# Hosts: the default node, nodes it lists on the mesh, and nodes the user added.
function Get-Hosts {
    $out = New-Object System.Collections.Generic.List[string]
    $out.Add($DefaultNode)
    $mesh = Get-Json $DefaultNode 'nodes'
    if ($mesh -and $mesh.nodes) { foreach ($n in $mesh.nodes) { if ($n.ip -and -not $out.Contains($n.ip)) { $out.Add($n.ip) } } }
    foreach ($h in Get-Saved) { if (-not $out.Contains($h)) { $out.Add($h) } }
    $out
}

function Open-Page([string]$hostName, [int]$port) { Start-Process "http://${hostName}:$port/" }

$form = New-Object System.Windows.Forms.Form
$form.Text = 'ManetNode'
$form.BackColor = $BG; $form.ForeColor = $TEXT
$form.Size = New-Object System.Drawing.Size(520, 560)
$form.MinimumSize = New-Object System.Drawing.Size(460, 420)
$form.Font = New-Object System.Drawing.Font('Segoe UI', 9.5)
$form.StartPosition = 'CenterScreen'

$title = New-Object System.Windows.Forms.Label
$title.Text = "ManetNode`r`nOPENMANET  FIELD EXTENSION"
$title.Font = New-Object System.Drawing.Font('Segoe UI', 11, [System.Drawing.FontStyle]::Bold)
$title.ForeColor = $CYAN; $title.AutoSize = $true; $title.Location = New-Object System.Drawing.Point(14, 10)
$form.Controls.Add($title)

$status = New-Object System.Windows.Forms.Label
$status.ForeColor = $SUB; $status.AutoSize = $true; $status.Location = New-Object System.Drawing.Point(16, 56)
$form.Controls.Add($status)

function New-Button([string]$text, [System.Drawing.Color]$fg) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $text.ToUpper(); $b.FlatStyle = 'Flat'; $b.FlatAppearance.BorderColor = $LINE
    $b.BackColor = $BG; $b.ForeColor = $fg; $b.AutoSize = $true
    $b.Font = New-Object System.Drawing.Font('Segoe UI', 8.5, [System.Drawing.FontStyle]::Bold)
    $b
}

$refresh = New-Button 'Refresh' $TEXT
$refresh.Anchor = 'Top,Right'; $refresh.Location = New-Object System.Drawing.Point(400, 14)
$form.Controls.Add($refresh)

$list = New-Object System.Windows.Forms.FlowLayoutPanel
$list.FlowDirection = 'TopDown'; $list.WrapContents = $false; $list.AutoScroll = $true
$list.Anchor = 'Top,Bottom,Left,Right'
$list.Location = New-Object System.Drawing.Point(12, 80); $list.Size = New-Object System.Drawing.Size(480, 380)
$form.Controls.Add($list)

$addBox = New-Object System.Windows.Forms.TextBox
$addBox.BackColor = $PANEL; $addBox.ForeColor = $TEXT; $addBox.BorderStyle = 'FixedSingle'
$addBox.Anchor = 'Bottom,Left,Right'
$addBox.Location = New-Object System.Drawing.Point(14, 474); $addBox.Size = New-Object System.Drawing.Size(370, 24)
$form.Controls.Add($addBox)
$addBtn = New-Button 'Add node' $CYAN
$addBtn.Anchor = 'Bottom,Right'; $addBtn.Location = New-Object System.Drawing.Point(392, 471)
$form.Controls.Add($addBtn)

function New-Card([string]$hostName, $s, [bool]$removable) {
    $card = New-Object System.Windows.Forms.Panel
    $card.BackColor = $PANEL; $card.Size = New-Object System.Drawing.Size(($list.ClientSize.Width - 24), 74)
    $card.Margin = New-Object System.Windows.Forms.Padding(2, 2, 2, 6)
    $colour = $MUTED
    if ($s -and $s.led -and $LED.ContainsKey([string]$s.led.color)) { $colour = $LED[[string]$s.led.color] }
    $dot = New-Object System.Windows.Forms.Panel
    $dot.Size = New-Object System.Drawing.Size(14, 14); $dot.Location = New-Object System.Drawing.Point(10, 12)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath; $path.AddEllipse(0, 0, 14, 14)
    $dot.Region = New-Object System.Drawing.Region($path); $dot.BackColor = $colour
    $card.Controls.Add($dot)
    $name = New-Object System.Windows.Forms.Label
    $name.Text = if ($s -and $s.node) { [string]$s.node } else { $hostName }
    $name.Font = New-Object System.Drawing.Font('Segoe UI', 11, [System.Drawing.FontStyle]::Bold)
    $name.ForeColor = $TEXT; $name.AutoSize = $true; $name.Location = New-Object System.Drawing.Point(30, 8)
    $card.Controls.Add($name)
    $meta = New-Object System.Windows.Forms.Label
    $parts = @($hostName)
    if (-not $s) { $parts += 'not answering' }
    else {
        $p = $s.power; if ($p -and $p.battery) { $p = $p.battery }
        if ($p -and $p.available -and $null -ne $p.battery_percent) { $parts += "battery $($p.battery_percent)%" }
        if ($s.mesh -and $null -ne $s.mesh.nodes) { $parts += "$($s.mesh.nodes) nodes on mesh" }
    }
    $meta.Text = ($parts -join '  ·  '); $meta.ForeColor = if ($s) { $SUB } else { $BAD }
    $meta.AutoSize = $true; $meta.Location = New-Object System.Drawing.Point(31, 32)
    $card.Controls.Add($meta)
    $om = New-Button 'OpenManet' $CYAN; $om.Location = New-Object System.Drawing.Point(($card.Width - 236), 40)
    $om.Tag = $hostName; $om.Add_Click({ Open-Page $this.Tag $OmPort }); $card.Controls.Add($om)
    $fe = New-Button 'FieldExtension' $CYAN; $fe.Location = New-Object System.Drawing.Point(($card.Width - 128), 40)
    $fe.Tag = $hostName; $fe.Add_Click({ Open-Page $this.Tag $FePort }); $card.Controls.Add($fe)
    if ($removable) {
        $rm = New-Button 'x' $MUTED; $rm.Location = New-Object System.Drawing.Point(($card.Width - 34), 4)
        $rm.Tag = $hostName
        $rm.Add_Click({ $h = $this.Tag; Set-Saved @(Get-Saved | Where-Object { $_ -ne $h }); Update-List })
        $card.Controls.Add($rm)
    }
    $card
}

function Update-List {
    $refresh.Enabled = $false; $status.Text = 'Checking nodes...'; $form.Refresh()
    $saved = Get-Saved
    # Nodes found on the mesh are only listed when they run the Field Extension (they answer);
    # 10.41.0.1 and nodes you added are always listed, answering or not.
    $cards = foreach ($h in Get-Hosts) {
        $sum = Get-Json $h 'summary'
        if ($sum -or $h -eq $DefaultNode -or $saved -contains $h) { New-Card $h $sum ($saved -contains $h) }
    }
    $list.SuspendLayout(); $list.Controls.Clear()
    foreach ($c in $cards) { $list.Controls.Add($c) }
    $list.ResumeLayout()
    $status.Text = "Checked $(Get-Date -Format 'HH:mm:ss')"
    $refresh.Enabled = $true
}

$refresh.Add_Click({ Update-List })
$addBtn.Add_Click({
    $h = $addBox.Text.Trim() -replace '^https?://', '' -replace '[:/].*$', ''
    if ($h -and -not ((Get-Saved) -contains $h) -and $h -ne $DefaultNode) { Set-Saved (@(Get-Saved) + $h) }
    $addBox.Text = ''; Update-List
})
$addBox.Add_KeyDown({ if ($_.KeyCode -eq 'Enter') { $addBtn.PerformClick(); $_.SuppressKeyPress = $true } })

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 30000
$timer.Add_Tick({ if ($form.ContainsFocus) { Update-List } })
$form.Add_Shown({ Update-List; $timer.Start() })
[void]$form.ShowDialog()
