from PIL import Image
import os

# 원본 이미지 열기
source = Image.open('docs/image.png')
print(f'Original size: {source.size}')

# 각 해상도별 크기 정의
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

# 각 밀도별로 리사이즈
for density, size in sizes.items():
    # 고품질 리샘플링으로 리사이즈
    resized = source.resize((size, size), Image.Resampling.LANCZOS)
    
    output_dir = f'app/src/main/res/mipmap-{density}'
    
    # ic_launcher.png
    resized.save(f'{output_dir}/ic_launcher.png', 'PNG', optimize=True)
    # ic_launcher_round.png
    resized.save(f'{output_dir}/ic_launcher_round.png', 'PNG', optimize=True)
    
    print(f'Created {density}: {size}x{size}')

print('All icons created!')
