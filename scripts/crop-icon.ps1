# Makes the app icon from the cabin and roof light of the taxi.
#
# The whole car is nearly five times wider than it is tall, so squaring it
# leaves a sliver that reads as a yellow smudge at sixteen pixels. The cabin
# with its chequered roof light is the compact, recognisable part of the same
# drawing, and it squares up without anything being scaled out of proportion.
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$Side = 512,
    # How far down the car band the cabin reaches, as a fraction of the logo.
    [double]$BandBottom = 0.40
)

Add-Type -AssemblyName System.Drawing

$logo = [System.Drawing.Bitmap]::FromFile($Source)

$rect = New-Object System.Drawing.Rectangle 0, 0, $logo.Width, $logo.Height
$data = $logo.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$bytes = New-Object byte[] ($data.Stride * $logo.Height)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$logo.UnlockBits($data)

$threshold = 40
$bottom = [int]($logo.Height * $BandBottom)

# PowerShell variable names are case-insensitive, so the crop size below is
# deliberately not called $side: it would be the same variable as the $Side
# parameter and would silently shrink the output canvas.
#
# The roof light is the only thing in the topmost rows, so its own extent is
# found there and the crop grows down from it to take the cabin with it.
$roofMinX = $logo.Width; $roofMaxX = -1; $roofTop = -1
$scanTo = [int]($logo.Height * 0.20)

for ($y = 0; $y -le $scanTo; $y++) {
    $row = $y * $data.Stride
    for ($x = 0; $x -lt $logo.Width; $x++) {
        if ($bytes[$row + ($x * 4) + 1] -gt $threshold) {
            if ($x -lt $roofMinX) { $roofMinX = $x }
            if ($x -gt $roofMaxX) { $roofMaxX = $x }
            if ($roofTop -lt 0) { $roofTop = $y }
        }
    }
}

Write-Output ("roof light: x {0}..{1}, top y {2}" -f $roofMinX, $roofMaxX, $roofTop)

# Square, centred on the roof light, reaching down into the cabin beneath it.
$height = $bottom - $roofTop
$width = $roofMaxX - $roofMinX + 1
$cropSide = [Math]::Max($width, $height)

# A touch of air so the shape does not sit against the icon's edge.
$cropSide = [int]($cropSide * 1.35)

$cx = [int]($roofMinX + ($width / 2))
$cy = [int]($roofTop + ($height / 2))
$sx = [Math]::Max(0, $cx - [int]($cropSide / 2))
$sy = [Math]::Max(0, $cy - [int]($cropSide / 2))
$cropSide = [Math]::Min($cropSide, [Math]::Min($logo.Width - $sx, $logo.Height - $sy))

Write-Output ("crop: {0}x{0} at ({1},{2})" -f $cropSide, $sx, $sy)

$canvas = New-Object System.Drawing.Bitmap $Side, $Side
$g = [System.Drawing.Graphics]::FromImage($canvas)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.Clear([System.Drawing.Color]::FromArgb(255, 0, 0, 0))

$src = New-Object System.Drawing.Rectangle $sx, $sy, $cropSide, $cropSide
$dst = New-Object System.Drawing.Rectangle 0, 0, $Side, $Side
$g.DrawImage($logo, $dst, $src, [System.Drawing.GraphicsUnit]::Pixel)

$g.Dispose()
$canvas.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Output ("wrote {0}  {1}x{1}" -f $Out, $Side)

$canvas.Dispose()
$logo.Dispose()
