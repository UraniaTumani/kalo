# Brings the logo down to the size it is actually drawn at.
#
# The crop came off a 1280px source, which is far more than a mark rendered
# 24-36px tall ever needs — even at three times the pixel density that is about
# 110px of height. The full-size file was 157 kB on every page load, and PNG
# compresses a re-encoded JPEG badly, so most of that was artefacts nobody sees.
#
# Downscaled only. The aspect ratio is the artwork's own.
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$Width = 480
)

Add-Type -AssemblyName System.Drawing

$src = [System.Drawing.Bitmap]::FromFile($Source)
$height = [int][Math]::Round($src.Height * ($Width / $src.Width))

$dst = New-Object System.Drawing.Bitmap $Width, $height
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.Clear([System.Drawing.Color]::FromArgb(255, 0, 0, 0))
$g.DrawImage($src, 0, 0, $Width, $height)
$g.Dispose()

$dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Output ("{0}x{1} -> {2}x{3}" -f $src.Width, $src.Height, $Width, $height)

$dst.Dispose()
$src.Dispose()
