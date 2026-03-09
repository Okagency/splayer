const fs = require('fs');
const { createCanvas, loadImage } = require('canvas');

async function resizeIcon() {
  const img = await loadImage('docs/image.png');
  console.log(`Original size: ${img.width}x${img.height}`);

  const sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
  };

  for (const [density, size] of Object.entries(sizes)) {
    const canvas = createCanvas(size, size);
    const ctx = canvas.getContext('2d');
    
    // 고품질 리샘플링
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(img, 0, 0, size, size);
    
    const buffer = canvas.toBuffer('image/png');
    const dir = `app/src/main/res/mipmap-${density}`;
    
    fs.writeFileSync(`${dir}/ic_launcher.png`, buffer);
    fs.writeFileSync(`${dir}/ic_launcher_round.png`, buffer);
    
    console.log(`Created ${density}: ${size}x${size}`);
  }
  
  console.log('All icons created!');
}

resizeIcon().catch(console.error);
