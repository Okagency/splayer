Add-Type -AssemblyName System.Drawing

$source = [System.Drawing.Image]::FromFile((Resolve-Path "docs/image.png"))
Write-Host "Original size: $($source.Width)x$($source.Height)"

$sizes = @{
    'mdpi' = 48
    'hdpi' = 72
    'xhdpi' = 96
    'xxhdpi' = 144
    'xxxhdpi' = 192
}

foreach ($density in $sizes.Keys) {
    $size = $sizes[$density]
    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    
    # 원본 이미지의 중심 70%만 사용 (1024의 70% = 약 717)
    # 가장자리를 잘라내고 중심부를 확대
    $cropSize = 717
    $cropOffset = ($source.Width - $cropSize) / 2
    
    $srcRect = New-Object System.Drawing.Rectangle($cropOffset, $cropOffset, $cropSize, $cropSize)
    $destRect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    
    $graphics.DrawImage($source, $destRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
    
    $dir = "app/src/main/res/mipmap-$density"
    $bitmap.Save("$dir/ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Save("$dir/ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
    
    Write-Host "Created $density $size (cropped and enlarged)"
    
    $graphics.Dispose()
    $bitmap.Dispose()
}

$source.Dispose()
Write-Host "All icons created with enlarged center area!"
