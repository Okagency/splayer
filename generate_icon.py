from PIL import Image, ImageDraw

def create_icon(size, filename):
    # 보라색/파란색 그라디언트 배경
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # 원형 배경 (보라색)
    draw.ellipse([0, 0, size-1, size-1], fill='#7C4DFF', outline='#5E35B1', width=max(1, size//48))

    # 재생 버튼 삼각형
    margin = size // 4
    triangle = [
        (margin, margin),
        (margin, size - margin),
        (size - margin, size // 2)
    ]
    draw.polygon(triangle, fill='white')

    img.save(filename, 'PNG')
    print(f"Created: {filename} ({size}x{size})")

# 각 크기별 아이콘 생성
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

for density, size in sizes.items():
    create_icon(size, f'app/src/main/res/mipmap-{density}/ic_launcher.png')
    create_icon(size, f'app/src/main/res/mipmap-{density}/ic_launcher_round.png')

print("All icons created successfully!")
