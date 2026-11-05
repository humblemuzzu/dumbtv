# Wallpapers for dumbtv home, from a Windows window: pick images, set which part of each one the
# TV shows, and send them. It does what scripts/wallpaper.sh does, without Git Bash or ImageMagick:
# each image is cropped to the TV's screen and scaled to its size (System.Drawing), pushed to the
# launcher's media folder, and a home.conf there turns the image background on with the chosen
# rotation and dim. It also lists the wallpapers on the TV with thumbnails and removes the ones
# you tick. The window speaks English, or Spanish when Windows does.
# Open it with Wallpapers.cmd. -SelfTest crops one image without a window or a TV.
param(
    [switch]$SelfTest,
    [switch]$NoShow,
    [string]$TestImage,
    [string]$TestOut,
    [string]$Lang
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$MEDIA = '/sdcard/Android/media/tv.dumbtv.home'
$PKG = 'tv.dumbtv.home'
$EXTS = @('.jpg', '.jpeg', '.png', '.bmp', '.gif')
$ROTATE = @('off', 'visit', 'hourly', 'daily', 'daytime')
$DIMS = @('0', '20', '40', '60', '80')

# --- words -----------------------------------------------------------------------------------

$WORDS = @{
    en = @{
        title = 'Wallpapers · dumbtv home'; tv = 'TV (IP:port)'; connect = 'Connect'; notConnected = 'Not connected'
        connecting = 'Connecting…'; images = 'Images (drop them here)'; add = 'Add…'; remove = 'Remove'; clear = 'Remove all'
        preview = 'Preview: what is inside the frame is what the TV shows'; keep = 'Part kept: {0}'
        fits = 'Already the shape of the screen: nothing is cropped'
        center = 'center'; left = 'further left'; right = 'further right'; up = 'higher'; down = 'lower'
        rotation = 'Wallpaper rotation'; rotations = @('Off', 'Every return home', 'Every hour', 'Every day', 'By time of day')
        dim = 'Image dim'; shuffle = 'Shuffle the order'; replace = 'Replace the wallpapers already on the TV'
        send = 'Send to TV'; onTv = 'Wallpapers on TV'; wipe = 'Clear the TV'
        count1 = '1 image in the list'; countN = '{0} images in the list'; empty = 'List is empty'; cantOpen = 'Could not open {0}'
        pick = 'Pick images'; filter = 'Images'; noAdb = 'adb was not found. Install Android platform-tools.'
        askTv = 'Type the TV address, for example 192.168.x.x:5555'
        unauthorized = 'The TV is asking for permission: allow USB debugging on the screen, then Connect again'
        refused = 'No answer: check the TV is on and ADB debugging is still on'; failed = 'Could not connect ({0})'
        noLauncher = 'Connected, but dumbtv home is not installed on this TV'
        connected = 'Connected · screen {0}×{1} · {2} wallpapers on the TV'; addFirst = 'Add at least one image'
        fitting = 'Fitting {0}…'; sending = 'Sending {0}…'; sendFailed = 'Could not send {0}: {1}'
        done = 'Done: {0} sent, {1} wallpapers on the TV.'; error = 'Error: {0}'
        tvTitle = 'Wallpapers on the TV'; tvHint = 'Tick the wallpapers to remove from the TV. The images on this computer are not touched.'
        none = 'There are no wallpapers on the TV.'; fetching = 'Fetching {0} from the TV ({1} of {2})…'; onTvN = '{0} wallpapers on the TV'
        ticked = '{0} wallpapers on the TV · {1} ticked'; tickAll = 'Tick all'; untickAll = 'Untick all'; delTicked = 'Remove ticked'; close = 'Close'
        askDel = 'Remove {0} wallpapers from the TV?'; delTitle = 'Remove from TV'
        askWipe = 'Remove every wallpaper on the TV? The images on this computer are not touched.'
        removed = 'Removed {0}. {1} wallpapers left on the TV.'; allGone = 'No wallpapers left on the TV; the background is black now.'
    }
    es = @{
        title = 'Fondos · dumbtv home'; tv = 'TV (IP:puerto)'; connect = 'Conectar'; notConnected = 'Sin conectar'
        connecting = 'Conectando…'; images = 'Imágenes (arrástralas aquí)'; add = 'Agregar…'; remove = 'Quitar'; clear = 'Quitar todas'
        preview = 'Vista previa: lo que está dentro del recuadro es lo que se verá en el TV'; keep = 'Parte que se conserva: {0}'
        fits = 'Ya tiene la forma de la pantalla: no se recorta'
        center = 'centro'; left = 'más a la izquierda'; right = 'más a la derecha'; up = 'más arriba'; down = 'más abajo'
        rotation = 'Rotación de fondos'; rotations = @('Apagada', 'Al volver al inicio', 'Cada hora', 'Cada día', 'Según la hora del día')
        dim = 'Oscurecer imagen'; shuffle = 'Mezclar el orden'; replace = 'Reemplazar los fondos que ya están en el TV'
        send = 'Enviar al TV'; onTv = 'Ver fondos del TV'; wipe = 'Borrar todos'
        count1 = '1 imagen en la lista'; countN = '{0} imágenes en la lista'; empty = 'Lista vacía'; cantOpen = 'No se pudo abrir {0}'
        pick = 'Elige las imágenes'; filter = 'Imágenes'; noAdb = 'No encontré adb. Instala Android platform-tools.'
        askTv = 'Escribe la dirección del TV, por ejemplo 192.168.x.x:5555'
        unauthorized = 'El TV pide permiso: acepta el aviso de depuración en la pantalla y vuelve a Conectar'
        refused = 'No responde: revisa que el TV esté encendido y que ADB debugging siga activado'; failed = 'No se pudo conectar ({0})'
        noLauncher = 'Conectado, pero dumbtv home no está instalado en este TV'
        connected = 'Conectado · pantalla {0}×{1} · {2} fondos en el TV'; addFirst = 'Agrega al menos una imagen'
        fitting = 'Ajustando {0}…'; sending = 'Enviando {0}…'; sendFailed = 'No se pudo enviar {0}: {1}'
        done = 'Listo: {0} enviados, {1} fondos en el TV.'; error = 'Error: {0}'
        tvTitle = 'Fondos en el TV'; tvHint = 'Marca los fondos que quieras quitar del TV. Las imágenes de tu computadora no se tocan.'
        none = 'No hay fondos en el TV.'; fetching = 'Trayendo {0} del TV ({1} de {2})…'; onTvN = '{0} fondos en el TV'
        ticked = '{0} fondos en el TV · {1} marcados'; tickAll = 'Marcar todas'; untickAll = 'Desmarcar todas'; delTicked = 'Borrar marcadas'; close = 'Cerrar'
        askDel = '¿Borrar {0} fondos del TV?'; delTitle = 'Borrar del TV'
        askWipe = '¿Borrar todos los fondos que hay en el TV? Las imágenes de tu computadora no se tocan.'
        removed = 'Borrados {0}. Quedan {1} fondos en el TV.'; allGone = 'Ya no quedan fondos en el TV; el fondo quedó en negro.'
    }
}
if (-not $Lang) { $Lang = [Globalization.CultureInfo]::CurrentUICulture.TwoLetterISOLanguageName }
$W = if ($WORDS.ContainsKey($Lang)) { $WORDS[$Lang] } else { $WORDS['en'] }
function T([string]$key) { return [string]$W[$key] }
function F([string]$key) { return [string]::Format((T $key), [object[]]$args) }

# --- images ----------------------------------------------------------------------------------

# Photos from a phone carry their rotation in EXIF; System.Drawing does not apply it on its own.
function Open-Image([string]$Path) {
    $img = [System.Drawing.Image]::FromFile($Path)
    if ($img.PropertyIdList -contains 0x0112) {
        $flip = switch ([int]$img.GetPropertyItem(0x0112).Value[0]) {
            3 { 'Rotate180FlipNone' } 6 { 'Rotate90FlipNone' } 8 { 'Rotate270FlipNone' } default { $null }
        }
        if ($flip) { $img.RotateFlip($flip) }
    }
    return $img
}

# The part of an $IW x $IH image with the screen's $SW:$SH shape. $Pos runs from 0 to 1 over the
# spare width or height: 0 left or top, 0.5 center, 1 right or bottom.
function Get-CropRect([int]$IW, [int]$IH, [double]$Pos, [int]$SW, [int]$SH) {
    $target = $SW / $SH
    if ($IW / $IH -gt $target) {
        $cw = [int][math]::Round($IH * $target); $ch = $IH
        $cx = [int][math]::Round(($IW - $cw) * $Pos); $cy = 0
    } else {
        $cw = $IW; $ch = [int][math]::Round($IW / $target)
        $cx = 0; $cy = [int][math]::Round(($IH - $ch) * $Pos)
    }
    return [System.Drawing.Rectangle]::new($cx, $cy, $cw, $ch)
}

# Crops $Path to the screen's shape at $Pos and writes it to $Out as a $SW x $SH JPEG.
function Save-Fitted([string]$Path, [double]$Pos, [int]$SW, [int]$SH, [string]$Out) {
    $img = Open-Image $Path
    try {
        $rect = Get-CropRect $img.Width $img.Height $Pos $SW $SH
        $bmp = New-Object System.Drawing.Bitmap $SW, $SH
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attr = New-Object System.Drawing.Imaging.ImageAttributes
        $attr.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $g.DrawImage($img, [System.Drawing.Rectangle]::new(0, 0, $SW, $SH),
            $rect.X, $rect.Y, $rect.Width, $rect.Height, [System.Drawing.GraphicsUnit]::Pixel, $attr)
        $enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
        $p = [System.Drawing.Imaging.EncoderParameters]::new(1)
        $p.Param[0] = [System.Drawing.Imaging.EncoderParameter]::new([System.Drawing.Imaging.Encoder]::Quality, [long]90)
        $bmp.Save($Out, $enc, $p)
        $g.Dispose(); $bmp.Dispose(); $attr.Dispose()
    } finally {
        $img.Dispose()
    }
}

if ($SelfTest) {
    Save-Fitted $TestImage 0.5 1920 1080 $TestOut
    $o = [System.Drawing.Image]::FromFile($TestOut)
    "selftest {0}x{1}" -f $o.Width, $o.Height
    $o.Dispose()
    return
}

# --- adb -------------------------------------------------------------------------------------

function Find-Adb {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($p in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", 'C:\platform-tools\adb.exe')) {
        if (Test-Path $p) { return $p }
    }
    return $null
}

$script:ADB = Find-Adb
$script:Serial = ''
# The last TV address, so the window remembers it. Nothing else is kept.
$SAVED = Join-Path $env:APPDATA 'dumbtv\wallpapers-tv.txt'

function Adb([string[]]$ArgList) {
    $out = & $script:ADB @ArgList 2>&1 | Out-String
    return $out.Trim()
}
function Sh([string]$Cmd) { return Adb @('-s', $script:Serial, 'shell', $Cmd) }

function Get-ScreenSize {
    $txt = Sh 'wm size'
    $over = [regex]::Match($txt, 'Override size:\s*(\d+)x(\d+)')
    $phys = [regex]::Match($txt, 'Physical size:\s*(\d+)x(\d+)')
    $m = if ($over.Success) { $over } else { $phys }
    if ($m.Success) { return @([int]$m.Groups[1].Value, [int]$m.Groups[2].Value) }
    return @(1920, 1080)
}

# The launcher merges a pushed home.conf once and skips a file identical to the last one, so each
# push carries its own time (as scripts/wallpaper.sh does).
function Push-Conf([string]$Lines) {
    $conf = Join-Path $env:TEMP ('dumbtv-home-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.conf')
    [IO.File]::WriteAllText($conf, $Lines + "wallpaper.pushed=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())`n")
    try { return Adb @('-s', $script:Serial, 'push', $conf, "$MEDIA/home.conf") }
    finally { Remove-Item $conf -ErrorAction SilentlyContinue }
}

# --- window ----------------------------------------------------------------------------------

[System.Windows.Forms.Application]::EnableVisualStyles()
$font = New-Object System.Drawing.Font 'Segoe UI', 10
$bg = [System.Drawing.Color]::FromArgb(24, 25, 29)
$panel = [System.Drawing.Color]::FromArgb(36, 37, 43)
$fg = [System.Drawing.Color]::FromArgb(236, 236, 238)
$muted = [System.Drawing.Color]::FromArgb(160, 160, 168)
$accent = [System.Drawing.Color]::FromArgb(61, 139, 255)
$bad = [System.Drawing.Color]::FromArgb(255, 120, 110)
$good = [System.Drawing.Color]::FromArgb(110, 220, 140)

$form = New-Object System.Windows.Forms.Form
$form.Text = T 'title'
$form.Size = New-Object System.Drawing.Size 1060, 720
$form.MinimumSize = New-Object System.Drawing.Size 980, 680
$form.StartPosition = 'CenterScreen'
$form.BackColor = $bg
$form.ForeColor = $fg
$form.Font = $font

function New-Label($text, $x, $y, $w = 200, $color = $fg) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $text; $l.Location = New-Object System.Drawing.Point $x, $y
    $l.Size = New-Object System.Drawing.Size $w, 24; $l.ForeColor = $color
    $form.Controls.Add($l); return $l
}
function New-Button($text, $x, $y, $w = 120, $h = 32) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $text; $b.Location = New-Object System.Drawing.Point $x, $y
    $b.Size = New-Object System.Drawing.Size $w, $h
    $b.FlatStyle = 'Flat'; $b.BackColor = $panel; $b.ForeColor = $fg
    $b.FlatAppearance.BorderColor = [System.Drawing.Color]::FromArgb(70, 72, 80)
    $form.Controls.Add($b); return $b
}

New-Label (T 'tv') 16 18 110 $muted | Out-Null
$ipBox = New-Object System.Windows.Forms.TextBox
$ipBox.Location = New-Object System.Drawing.Point 128, 15
$ipBox.Size = New-Object System.Drawing.Size 180, 26
$ipBox.Text = if ($env:ADB_SERIAL) { $env:ADB_SERIAL } elseif (Test-Path $SAVED) { (Get-Content $SAVED -TotalCount 1).Trim() } else { '' }
$ipBox.BackColor = $panel; $ipBox.ForeColor = $fg; $ipBox.BorderStyle = 'FixedSingle'
$form.Controls.Add($ipBox)
$connectBtn = New-Button (T 'connect') 318 12 110
$status = New-Label (T 'notConnected') 440 18 590 $muted

New-Label (T 'images') 16 62 300 $muted | Out-Null
$list = New-Object System.Windows.Forms.ListBox
$list.Location = New-Object System.Drawing.Point 16, 88
$list.Size = New-Object System.Drawing.Size 330, 400
$list.BackColor = $panel; $list.ForeColor = $fg; $list.BorderStyle = 'None'
$list.HorizontalScrollbar = $true
$list.SelectionMode = 'MultiExtended'
$list.AllowDrop = $true
$form.Controls.Add($list)
$addBtn = New-Button (T 'add') 16 496 104
$removeBtn = New-Button (T 'remove') 128 496 104
$clearBtn = New-Button (T 'clear') 240 496 106

New-Label (T 'preview') 366 62 660 $muted | Out-Null
$preview = New-Object System.Windows.Forms.PictureBox
$preview.Location = New-Object System.Drawing.Point 366, 88
$preview.Size = New-Object System.Drawing.Size 660, 371
$preview.BackColor = [System.Drawing.Color]::Black
$form.Controls.Add($preview)
$posLabel = New-Label '' 366 466 600
$posBar = New-Object System.Windows.Forms.TrackBar
$posBar.Location = New-Object System.Drawing.Point 366, 490
$posBar.Size = New-Object System.Drawing.Size 660, 40
$posBar.Minimum = 0; $posBar.Maximum = 100; $posBar.Value = 50; $posBar.TickFrequency = 10
$posBar.Enabled = $false
$form.Controls.Add($posBar)

New-Label (T 'rotation') 16 548 200 $muted | Out-Null
$rotBox = New-Object System.Windows.Forms.ComboBox
$rotBox.Location = New-Object System.Drawing.Point 16, 572
$rotBox.Size = New-Object System.Drawing.Size 200, 28
$rotBox.DropDownStyle = 'DropDownList'
$rotBox.FlatStyle = 'Flat'; $rotBox.BackColor = $panel; $rotBox.ForeColor = $fg
$W['rotations'] | ForEach-Object { [void]$rotBox.Items.Add($_) }
$rotBox.SelectedIndex = 2
$form.Controls.Add($rotBox)

New-Label (T 'dim') 232 548 140 $muted | Out-Null
$dimBox = New-Object System.Windows.Forms.ComboBox
$dimBox.Location = New-Object System.Drawing.Point 232, 572
$dimBox.Size = New-Object System.Drawing.Size 110, 28
$dimBox.DropDownStyle = 'DropDownList'
$dimBox.FlatStyle = 'Flat'; $dimBox.BackColor = $panel; $dimBox.ForeColor = $fg
$DIMS | ForEach-Object { [void]$dimBox.Items.Add("$_ %") }
$dimBox.SelectedIndex = 1
$form.Controls.Add($dimBox)

$shuffle = New-Object System.Windows.Forms.CheckBox
$shuffle.Text = T 'shuffle'; $shuffle.Checked = $true
$shuffle.Location = New-Object System.Drawing.Point 366, 556
$shuffle.Size = New-Object System.Drawing.Size 330, 26
$form.Controls.Add($shuffle)
$replace = New-Object System.Windows.Forms.CheckBox
$replace.Text = T 'replace'
$replace.Location = New-Object System.Drawing.Point 366, 584
$replace.Size = New-Object System.Drawing.Size 340, 26
$form.Controls.Add($replace)

$sendBtn = New-Button (T 'send') 856 552 170 58
$sendBtn.BackColor = $accent; $sendBtn.ForeColor = [System.Drawing.Color]::White
$sendBtn.Font = New-Object System.Drawing.Font 'Segoe UI Semibold', 11
$onTvBtn = New-Button (T 'onTv') 716 552 132 26
$wipeBtn = New-Button (T 'wipe') 716 584 132 26

$progress = New-Object System.Windows.Forms.ProgressBar
$progress.Location = New-Object System.Drawing.Point 16, 622
$progress.Size = New-Object System.Drawing.Size 1010, 10
$form.Controls.Add($progress)
$log = New-Label '' 16 640 1010 $muted

# --- state -----------------------------------------------------------------------------------

# One entry per image: its path, where the crop sits (0..1) and a thumbnail for the preview.
$script:items = New-Object System.Collections.ArrayList
$script:screen = @(1920, 1080)
$script:connected = $false

function Say([string]$text) { $log.Text = $text; $form.Refresh() }
function Count-Text { if ($script:items.Count -eq 1) { T 'count1' } else { F 'countN' $script:items.Count } }

function Add-Images([string[]]$paths) {
    foreach ($p in $paths) {
        if (Test-Path $p -PathType Container) {
            Add-Images (Get-ChildItem $p -File | ForEach-Object FullName); continue
        }
        if ($EXTS -notcontains [IO.Path]::GetExtension($p).ToLower()) { continue }
        if ($script:items | Where-Object { $_.Path -eq $p }) { continue }
        try {
            $img = Open-Image $p
            $tw = 660; $th = [int][math]::Round($img.Height * 660 / $img.Width)
            if ($th -gt 371) { $th = 371; $tw = [int][math]::Round($img.Width * 371 / $img.Height) }
            $thumb = New-Object System.Drawing.Bitmap $img, $tw, $th
            [void]$script:items.Add([pscustomobject]@{ Path = $p; Pos = 0.5; W = $img.Width; H = $img.Height; Thumb = $thumb })
            $img.Dispose()
            [void]$list.Items.Add(("{0}   ({1}×{2})" -f [IO.Path]::GetFileName($p), $script:items[-1].W, $script:items[-1].H))
        } catch {
            Say (F 'cantOpen' ([IO.Path]::GetFileName($p)))
        }
    }
    if ($list.SelectedIndex -lt 0 -and $list.Items.Count -gt 0) { $list.SelectedIndex = 0 }
    Say (Count-Text)
}

function Current { if ($list.SelectedIndex -ge 0) { return $script:items[$list.SelectedIndex] }; return $null }

function Pos-Name($it) {
    $wide = $it.W / $it.H -gt $script:screen[0] / $script:screen[1]
    $v = $it.Pos
    if ([math]::Abs($v - 0.5) -lt 0.05) { return T 'center' }
    if ($wide) { if ($v -lt 0.5) { T 'left' } else { T 'right' } }
    else { if ($v -lt 0.5) { T 'up' } else { T 'down' } }
}

$preview.Add_Paint({
    param($s, $e)
    $it = Current
    if (-not $it) { return }
    $g = $e.Graphics
    $t = $it.Thumb
    $ox = [int](($preview.Width - $t.Width) / 2); $oy = [int](($preview.Height - $t.Height) / 2)
    $g.DrawImage($t, $ox, $oy, $t.Width, $t.Height)
    $r = Get-CropRect $it.W $it.H $it.Pos $script:screen[0] $script:screen[1]
    $k = $t.Width / $it.W
    $rx = $ox + [int]($r.X * $k); $ry = $oy + [int]($r.Y * $k)
    $rw = [int]($r.Width * $k); $rh = [int]($r.Height * $k)
    # Darkens what gets cropped away and outlines what stays.
    $shade = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(170, 0, 0, 0))
    $outer = [System.Drawing.Region]::new([System.Drawing.Rectangle]::new($ox, $oy, $t.Width, $t.Height))
    $outer.Exclude([System.Drawing.Rectangle]::new($rx, $ry, $rw, $rh))
    $g.FillRegion($shade, $outer)
    $pen = New-Object System.Drawing.Pen $accent, 2
    $g.DrawRectangle($pen, $rx, $ry, $rw - 1, $rh - 1)
    $shade.Dispose(); $outer.Dispose(); $pen.Dispose()
})

function Show-Current {
    $it = Current
    if ($it) {
        $fits = [math]::Abs($it.W / $it.H - $script:screen[0] / $script:screen[1]) -lt 0.01
        $posBar.Enabled = -not $fits
        $posBar.Value = [int]($it.Pos * 100)
        $posLabel.Text = if ($fits) { T 'fits' } else { F 'keep' (Pos-Name $it) }
    } else {
        $posBar.Enabled = $false; $posLabel.Text = ''
    }
    $preview.Invalidate()
}

# --- actions ---------------------------------------------------------------------------------

function Show-Status([int]$n) {
    $status.ForeColor = $good
    $status.Text = F 'connected' $script:screen[0] $script:screen[1] $n
}
function Tv-Count { return [int]((Sh "ls $MEDIA/wallpapers 2>/dev/null | wc -l").Trim()) }

function Connect-Tv {
    if (-not $script:ADB) {
        [System.Windows.Forms.MessageBox]::Show((T 'noAdb'), (T 'title')) | Out-Null
        return $false
    }
    $script:Serial = $ipBox.Text.Trim()
    if (-not $script:Serial) { $status.ForeColor = $muted; $status.Text = T 'askTv'; return $false }
    if ($script:Serial -notmatch ':\d+$') { $script:Serial += ':5555' }
    $status.ForeColor = $muted; $status.Text = T 'connecting'; $form.Refresh()
    $out = Adb @('connect', $script:Serial)
    $state = Adb @('-s', $script:Serial, 'get-state')
    if ($state -ne 'device') {
        $script:connected = $false
        $status.ForeColor = $bad
        $status.Text = if ($out -match 'unauthorized' -or $state -match 'unauthorized') { T 'unauthorized' }
            elseif ($out -match 'refused|failed|cannot') { T 'refused' }
            else { F 'failed' $state }
        return $false
    }
    if ((Sh "pm path $PKG") -notmatch '^package:') {
        $status.ForeColor = $bad; $status.Text = T 'noLauncher'
        return $false
    }
    New-Item -ItemType Directory -Force (Split-Path $SAVED) | Out-Null
    Set-Content $SAVED $script:Serial
    $script:screen = Get-ScreenSize
    $script:connected = $true
    Show-Status (Tv-Count)
    Show-Current
    return $true
}

function Send-Wallpapers {
    if ($script:items.Count -eq 0) { Say (T 'addFirst'); return }
    if (-not $script:connected -and -not (Connect-Tv)) { return }
    $sw = $script:screen[0]; $sh = $script:screen[1]
    $work = Join-Path $env:TEMP ('dumbtv-wall-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force $work | Out-Null
    $sendBtn.Enabled = $false
    try {
        $progress.Maximum = $script:items.Count * 2 + 2; $progress.Value = 0
        $files = @()
        $n = 0
        foreach ($it in $script:items) {
            $n++
            # Names keep to letters, digits, - and _ (the launcher sorts by name and rotate=daytime
            # reads words in it); a name already taken gets the image's number, as wallpaper.sh does.
            $name = [IO.Path]::GetFileNameWithoutExtension($it.Path) -replace '[^A-Za-z0-9_-]', '-'
            if ($shuffle.Checked) { $name = '{0:D5}-{1}' -f (Get-Random -Maximum 100000), $name }
            while (Test-Path (Join-Path $work "$name.jpg")) { $name = "$name-$n" }
            Say (F 'fitting' ([IO.Path]::GetFileName($it.Path)))
            $out = Join-Path $work "$name.jpg"
            Save-Fitted $it.Path $it.Pos $sw $sh $out
            $files += $out
            $progress.Value++
        }
        Sh "mkdir -p $MEDIA/wallpapers" | Out-Null
        if ($replace.Checked) { Sh "rm -f $MEDIA/wallpapers/*" | Out-Null }
        $progress.Value++
        foreach ($f in $files) {
            $leaf = [IO.Path]::GetFileName($f)
            Say (F 'sending' $leaf)
            $r = Adb @('-s', $script:Serial, 'push', $f, "$MEDIA/wallpapers/$leaf")
            if ($r -notmatch 'pushed') { throw (F 'sendFailed' $leaf $r) }
            $progress.Value++
        }
        $rot = $ROTATE[$rotBox.SelectedIndex]
        $dim = $DIMS[$dimBox.SelectedIndex]
        $r = Push-Conf "background=image`nrotate=$rot`ndim=$dim`n"
        if ($r -notmatch 'pushed') { throw (F 'sendFailed' 'home.conf' $r) }
        Sh "am start -n $PKG/.Home" | Out-Null
        $progress.Value = $progress.Maximum
        $left = Tv-Count
        Say (F 'done' $files.Count $left)
        Show-Status $left
    } catch {
        Say (F 'error' $_.Exception.Message)
    } finally {
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
        $sendBtn.Enabled = $true
    }
}

# The wallpapers on the TV as thumbnails; the ticked ones are removed. $CaptureTo draws the window
# into an image file instead of opening it, for testing.
function Show-TvWallpapers([string]$CaptureTo) {
    if (-not $script:connected -and -not (Connect-Tv)) { return }
    $names = @((Sh "ls $MEDIA/wallpapers 2>/dev/null") -split "`r?`n" |
        Where-Object { $_ -match '\S' -and $_ -notmatch 'No such file' })
    if ($names.Count -eq 0) {
        [System.Windows.Forms.MessageBox]::Show((T 'none'), (T 'tvTitle')) | Out-Null
        return
    }
    $tmp = Join-Path $env:TEMP ('dumbtv-wall-view-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force $tmp | Out-Null

    $dlg = New-Object System.Windows.Forms.Form
    $dlg.Text = T 'tvTitle'
    $dlg.Size = New-Object System.Drawing.Size 940, 640
    $dlg.StartPosition = 'CenterParent'
    $dlg.BackColor = $bg; $dlg.ForeColor = $fg; $dlg.Font = $font

    $hint = New-Object System.Windows.Forms.Label
    $hint.Text = T 'tvHint'
    $hint.Location = New-Object System.Drawing.Point 16, 14
    $hint.Size = New-Object System.Drawing.Size 890, 24; $hint.ForeColor = $muted
    $dlg.Controls.Add($hint)

    $thumbs = New-Object System.Windows.Forms.ImageList
    $thumbs.ColorDepth = 'Depth32Bit'
    $thumbs.ImageSize = New-Object System.Drawing.Size 256, 144
    $view = New-Object System.Windows.Forms.ListView
    $view.Location = New-Object System.Drawing.Point 16, 44
    $view.Size = New-Object System.Drawing.Size 892, 490
    $view.View = 'LargeIcon'; $view.CheckBoxes = $true; $view.LargeImageList = $thumbs
    $view.BackColor = $panel; $view.ForeColor = $fg; $view.BorderStyle = 'None'
    $dlg.Controls.Add($view)

    $info = New-Object System.Windows.Forms.Label
    $info.Location = New-Object System.Drawing.Point 16, 552
    $info.Size = New-Object System.Drawing.Size 420, 24; $info.ForeColor = $muted
    $dlg.Controls.Add($info)

    function Dlg-Button($text, $x, $w) {
        $b = New-Object System.Windows.Forms.Button
        $b.Text = $text; $b.Location = New-Object System.Drawing.Point $x, 546
        $b.Size = New-Object System.Drawing.Size $w, 34
        $b.FlatStyle = 'Flat'; $b.BackColor = $panel; $b.ForeColor = $fg
        $b.FlatAppearance.BorderColor = [System.Drawing.Color]::FromArgb(70, 72, 80)
        $dlg.Controls.Add($b); return $b
    }
    $allBtn = Dlg-Button (T 'tickAll') 450 130
    $delBtn = Dlg-Button (T 'delTicked') 588 160
    $delBtn.BackColor = [System.Drawing.Color]::FromArgb(150, 45, 45)
    $closeBtn = Dlg-Button (T 'close') 756 152

    # Each wallpaper comes back from the TV for its thumbnail, letterboxed when it is not 16:9.
    $k = 0
    foreach ($n in $names) {
        $k++
        Say (F 'fetching' $n $k $names.Count)
        $local = Join-Path $tmp $n
        Adb @('-s', $script:Serial, 'pull', "$MEDIA/wallpapers/$n", $local) | Out-Null
        $tb = New-Object System.Drawing.Bitmap 256, 144
        $g = [System.Drawing.Graphics]::FromImage($tb)
        $g.Clear([System.Drawing.Color]::Black)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        try {
            $img = [System.Drawing.Image]::FromFile($local)
            $sc = [math]::Min(256 / $img.Width, 144 / $img.Height)
            $iw = [int]($img.Width * $sc); $ih = [int]($img.Height * $sc)
            $g.DrawImage($img, [int]((256 - $iw) / 2), [int]((144 - $ih) / 2), $iw, $ih)
            $img.Dispose()
        } catch { }
        $g.Dispose()
        $thumbs.Images.Add($n, $tb)
        [void]$view.Items.Add((New-Object System.Windows.Forms.ListViewItem $n, $n))
    }
    Say (F 'onTvN' $names.Count)

    $refreshInfo = {
        $c = @($view.CheckedItems).Count
        $info.Text = F 'ticked' $view.Items.Count $c
        $delBtn.Enabled = $c -gt 0
    }
    & $refreshInfo
    $view.Add_ItemChecked({ & $refreshInfo })
    $allBtn.Add_Click({
        $on = @($view.CheckedItems).Count -lt $view.Items.Count
        foreach ($it in $view.Items) { $it.Checked = $on }
        $allBtn.Text = if ($on) { T 'untickAll' } else { T 'tickAll' }
    })
    $delBtn.Add_Click({
        $sel = @($view.CheckedItems)
        $ok = [System.Windows.Forms.MessageBox]::Show((F 'askDel' $sel.Count), (T 'delTitle'), 'YesNo', 'Warning')
        if ($ok -ne 'Yes') { return }
        foreach ($it in $sel) {
            Sh "rm -f '$MEDIA/wallpapers/$($it.Text)'" | Out-Null
            $view.Items.Remove($it)
        }
        $left = Tv-Count
        if ($left -eq 0) { Push-Conf "background=black`n" | Out-Null; Say (T 'allGone') }
        else { Say (F 'removed' $sel.Count $left) }
        Show-Status $left
        Sh "am start -n $PKG/.Home" | Out-Null
        & $refreshInfo
    })
    $closeBtn.Add_Click({ $dlg.Close() })

    if ($CaptureTo) {
        $dlg.CreateControl(); [void]$dlg.Handle; foreach ($c in $dlg.Controls) { [void]$c.Handle }
        $bmp = New-Object System.Drawing.Bitmap $dlg.Width, $dlg.Height
        $dlg.DrawToBitmap($bmp, [System.Drawing.Rectangle]::new(0, 0, $dlg.Width, $dlg.Height))
        $bmp.Save($CaptureTo); $bmp.Dispose()
    } else {
        [void]$dlg.ShowDialog($form)
    }
    $thumbs.Dispose(); $dlg.Dispose()
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

$connectBtn.Add_Click({ [void](Connect-Tv) })
$addBtn.Add_Click({
    $d = New-Object System.Windows.Forms.OpenFileDialog
    $d.Multiselect = $true
    $d.Title = T 'pick'
    $d.Filter = (T 'filter') + '|*.jpg;*.jpeg;*.png;*.bmp;*.gif'
    $d.InitialDirectory = [Environment]::GetFolderPath('UserProfile') + '\Downloads'
    if ($d.ShowDialog() -eq 'OK') { Add-Images $d.FileNames }
})
$removeBtn.Add_Click({
    $sel = @($list.SelectedIndices) | Sort-Object -Descending
    foreach ($i in $sel) { $script:items[$i].Thumb.Dispose(); $script:items.RemoveAt($i); $list.Items.RemoveAt($i) }
    if ($list.Items.Count -gt 0) { $list.SelectedIndex = 0 }
    Show-Current; Say (Count-Text)
})
$clearBtn.Add_Click({
    foreach ($it in $script:items) { $it.Thumb.Dispose() }
    $script:items.Clear(); $list.Items.Clear(); Show-Current; Say (T 'empty')
})
$list.Add_SelectedIndexChanged({ Show-Current })
$list.Add_DragEnter({ param($s, $e) if ($e.Data.GetDataPresent([Windows.Forms.DataFormats]::FileDrop)) { $e.Effect = 'Copy' } })
$list.Add_DragDrop({ param($s, $e) Add-Images ($e.Data.GetData([Windows.Forms.DataFormats]::FileDrop)) })
$posBar.Add_Scroll({
    $it = Current
    if ($it) { $it.Pos = $posBar.Value / 100; $posLabel.Text = F 'keep' (Pos-Name $it); $preview.Invalidate() }
})
$sendBtn.Add_Click({ Send-Wallpapers })
$onTvBtn.Add_Click({ Show-TvWallpapers })
$wipeBtn.Add_Click({
    if (-not $script:connected -and -not (Connect-Tv)) { return }
    $ok = [System.Windows.Forms.MessageBox]::Show((T 'askWipe'), (T 'delTitle'), 'YesNo', 'Warning')
    if ($ok -ne 'Yes') { return }
    Sh "rm -f $MEDIA/wallpapers/*" | Out-Null
    Push-Conf "background=black`n" | Out-Null
    Sh "am start -n $PKG/.Home" | Out-Null
    Show-Status 0
    Say (T 'allGone')
})

$form.Add_Shown({ if (-not $script:ADB) { $status.Text = T 'noAdb' } elseif ($ipBox.Text) { [void](Connect-Tv) } else { $status.Text = T 'askTv' } })
# -NoShow builds everything without opening the window, so another script can drive the actions.
if (-not $NoShow) { [void]$form.ShowDialog() }
