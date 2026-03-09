#!/usr/bin/env python3
"""
Android 앱 아이콘 생성 스크립트
docs/image.png를 다양한 해상도로 변환하여 mipmap 폴더에 배치
"""

import os
import sys
from PIL import Image

# 아이콘 해상도 정의
ICON_SIZES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

def generate_icons():
    # 경로 설정
    source_image = 'docs/image.png'
    res_dir = 'app/src/main/res'

    if not os.path.exists(source_image):
        print(f"❌ 소스 이미지를 찾을 수 없습니다: {source_image}")
        return False

    print(f"✅ 소스 이미지 로드: {source_image}")

    try:
        # 원본 이미지 열기
        img = Image.open(source_image)
        print(f"   원본 크기: {img.size}")

        # RGBA 모드로 변환 (투명도 지원)
        if img.mode != 'RGBA':
            img = img.convert('RGBA')

        # 각 해상도별로 아이콘 생성
        for mipmap_dir, size in ICON_SIZES.items():
            output_dir = os.path.join(res_dir, mipmap_dir)
            os.makedirs(output_dir, exist_ok=True)

            # 아이콘 크기 조정
            resized_img = img.resize((size, size), Image.Resampling.LANCZOS)

            # ic_launcher.png 저장
            output_path = os.path.join(output_dir, 'ic_launcher.png')
            resized_img.save(output_path, 'PNG')
            print(f"✅ 생성됨: {output_path} ({size}x{size})")

            # ic_launcher_round.png도 생성 (동일한 이미지 사용)
            output_path_round = os.path.join(output_dir, 'ic_launcher_round.png')
            resized_img.save(output_path_round, 'PNG')
            print(f"✅ 생성됨: {output_path_round} ({size}x{size})")

        print("\n🎉 모든 아이콘이 성공적으로 생성되었습니다!")
        return True

    except Exception as e:
        print(f"❌ 오류 발생: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == '__main__':
    success = generate_icons()
    sys.exit(0 if success else 1)
