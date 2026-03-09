const fs = require('fs');
const path = require('path');

console.log('Installing canvas package...');
require('child_process').execSync('npm install canvas --no-save', { stdio: 'inherit' });

const { createCanvas } = require('canvas');

const sizes = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192
};

const resDir = 'app/src/main/res';

function createIcon(size) {
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');

  // 배경 - 보라색에서 핑크 그라데이션
  const gradient = ctx.createLinearGradient(0, 0, size, size);
  gradient.addColorStop(0, '#8B5CF6');    // 보라색
  gradient.addColorStop(0.5, '#D946EF');  // 자주색
  gradient.addColorStop(1, '#F472B6');    // 핑크색

  // 둥근 사각형 배경
  const radius = size * 0.2;
  ctx.fillStyle = gradient;
  ctx.beginPath();
  ctx.roundRect(0, 0, size, size, radius);
  ctx.fill();

  // 'S' 글자
  ctx.fillStyle = '#FFFFFF';
  ctx.font = `bold ${size * 0.7}px Arial`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('S', size / 2, size / 2);

  return canvas;
}

async function generateIcons() {
  try {
    for (const [mipmapDir, size] of Object.entries(sizes)) {
      const outputDir = path.join(resDir, mipmapDir);

      // Ensure directory exists
      if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true });
      }

      // Create icon
      const canvas = createIcon(size);
      const buffer = canvas.toBuffer('image/png');

      // Save ic_launcher.png
      const outputPath = path.join(outputDir, 'ic_launcher.png');
      fs.writeFileSync(outputPath, buffer);
      console.log(`✅ Generated: ${outputPath} (${size}x${size})`);

      // Save ic_launcher_round.png
      const outputPathRound = path.join(outputDir, 'ic_launcher_round.png');
      fs.writeFileSync(outputPathRound, buffer);
      console.log(`✅ Generated: ${outputPathRound} (${size}x${size})`);
    }

    console.log('\n🎉 All icons generated successfully!');
  } catch (error) {
    console.error('❌ Error:', error);
    process.exit(1);
  }
}

generateIcons();
