# Generate 1200x760 screenshot placeholder images (QR code centered) for docs/images.
# Re-run anytime; overwrites the four screenshot-*.png files.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$src   = Join-Path $PSScriptRoot '..\docs\images\wechat-qr.png'
$outDir = Join-Path $PSScriptRoot '..\docs\images'
$names = @(
    'screenshot-01-sql-preview.png',
    'screenshot-02-log-panel.png',
    'screenshot-03-sql-to-java.png',
    'screenshot-04-xml-preview.png'
)
$W = 1200; $H = 760

$qr = [System.Drawing.Image]::FromFile($src)
$fontTitle = New-Object System.Drawing.Font('Segoe UI', 28, [System.Drawing.FontStyle]::Bold)
$fontSub   = New-Object System.Drawing.Font('Segoe UI', 18)
$fontFoot  = New-Object System.Drawing.Font('Segoe UI', 14)
$brush = [System.Drawing.Brushes]::DimGray
$borderPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(200,200,200), 2)

foreach ($n in $names) {
    $bmp = New-Object System.Drawing.Bitmap($W, $H)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::FromArgb(242,242,242))
    $g.DrawRectangle($borderPen, 1, 1, $W-3, $H-3)

    $s = $g.MeasureString($n, $fontTitle)
    $g.DrawString($n, $fontTitle, $brush, ($W-$s.Width)/2, 55)

    $sub = 'Screenshot placeholder - replace this file'
    $s = $g.MeasureString($sub, $fontSub)
    $g.DrawString($sub, $fontSub, $brush, ($W-$s.Width)/2, 125)

    $qrSize = 340
    $g.DrawImage($qr, ($W-$qrSize)/2, ($H-$qrSize)/2 + 35, $qrSize, $qrSize)

    $foot = 'Target size 1200x760 - keep the same filename after replacement'
    $s = $g.MeasureString($foot, $fontFoot)
    $g.DrawString($foot, $fontFoot, $brush, ($W-$s.Width)/2, $H-75)

    $bmp.Save((Join-Path $outDir $n), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}
$qr.Dispose()
Write-Output 'placeholders generated'
