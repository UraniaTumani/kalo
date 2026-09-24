# Crops the supplied brand artwork down to the logo itself.
#
# The source is a square image with the logo floating in a large black field,
# so most of it is padding. This finds the bounding box of everything that is
# not background and writes two assets from it: the full lockup, and the taxi
# silhouette alone for use as an icon.
#
# Nothing is scaled or stretched — only cropped — so the proportions are the
# artwork's own.
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$OutDir
)

Add-Type -AssemblyName System.Drawing

$bitmap = [System.Drawing.Bitmap]::FromFile($Source)
Write-Output ("source: {0}x{1}" -f $bitmap.Width, $bitmap.Height)

# Read the whole image once through LockBits. GetPixel on a million pixels is
# minutes of work in PowerShell; this is a fraction of a second.
$rect = New-Object System.Drawing.Rectangle 0, 0, $bitmap.Width, $bitmap.Height
$data = $bitmap.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$bytes = New-Object byte[] ($data.Stride * $bitmap.Height)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$bitmap.UnlockBits($data)

# Well above JPEG noise in the black field, well below the ink of the artwork.
$threshold = 40

$minX = $bitmap.Width; $minY = $bitmap.Height; $maxX = -1; $maxY = -1
# The taxi sits above the wordmark; tracked separately so it can be an icon.
$carMaxY = -1

for ($y = 0; $y -lt $bitmap.Height; $y++) {
    $row = $y * $data.Stride
    for ($x = 0; $x -lt $bitmap.Width; $x++) {
        $i = $row + ($x * 4)
        # BGRA. Green alone is a good enough stand-in for brightness here:
        # both the white and the yellow are high in it, the field is not.
        if ($bytes[$i + 1] -gt $threshold) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}

if ($maxX -lt 0) { throw "found nothing but background; is the threshold right?" }

Write-Output ("logo bounds: x {0}..{1}  y {2}..{3}" -f $minX, $maxX, $minY, $maxY)

# A little of the black field kept around the artwork, so the logo has room to
# breathe inside whatever container it sits in rather than touching the edges.
$pad = [int](($maxX - $minX) * 0.04)
$cx = [Math]::Max(0, $minX - $pad)
$cy = [Math]::Max(0, $minY - $pad)
$cw = [Math]::Min($bitmap.Width - $cx, ($maxX - $minX) + 1 + (2 * $pad))
$ch = [Math]::Min($bitmap.Height - $cy, ($maxY - $minY) + 1 + (2 * $pad))

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Save-Crop($x, $y, $w, $h, $name) {
    $crop = New-Object System.Drawing.Rectangle $x, $y, $w, $h
    $out = $bitmap.Clone($crop, $bitmap.PixelFormat)
    $path = Join-Path $OutDir $name
    $out.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose()
    Write-Output ("wrote {0}  {1}x{2}" -f $path, $w, $h)
}

Save-Crop $cx $cy $cw $ch "mr-taxi-logo.png"

# The silhouette alone: from the top of the artwork down to where the car ends.
# Found by walking down from the top until a row goes empty, which is the gap
# between the car and the wordmark.
$gapY = -1
for ($y = $minY; $y -le $maxY; $y++) {
    $row = $y * $data.Stride
    $lit = $false
    for ($x = $minX; $x -le $maxX; $x++) {
        if ($bytes[$row + ($x * 4) + 1] -gt $threshold) { $lit = $true; break }
    }
    if (-not $lit -and $y -gt ($minY + 10)) { $gapY = $y; break }
}

if ($gapY -gt 0) {
    Write-Output ("car ends at y {0}" -f $gapY)

    # Squared up around the silhouette so an icon is not letterboxed.
    $carH = $gapY - $minY
    $carW = $maxX - $minX + 1
    $side = [Math]::Max($carW, $carH) + (2 * $pad)
    $sx = [Math]::Max(0, [int]($minX + ($carW / 2) - ($side / 2)))
    $sy = [Math]::Max(0, [int]($minY + ($carH / 2) - ($side / 2)))
    $side = [Math]::Min($side, [Math]::Min($bitmap.Width - $sx, $bitmap.Height - $sy))

    Save-Crop $sx $sy $side $side "mr-taxi-mark.png"
} else {
    Write-Output "could not find the gap under the car; skipped the icon crop"
}

$bitmap.Dispose()
