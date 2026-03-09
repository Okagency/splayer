#!/usr/bin/env python3
"""
Generate Android app icons from source image
"""
from PIL import Image
import os

# Icon sizes for different densities
ICON_SIZES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

def generate_icons():
    source_image = 'docs/image.png'
    base_output_dir = 'app/src/main/res'

    # Open source image
    img = Image.open(source_image)
    print(f"Source image size: {img.size}")

    # Generate icons for each density
    for density, size in ICON_SIZES.items():
        output_dir = os.path.join(base_output_dir, f'mipmap-{density}')
        os.makedirs(output_dir, exist_ok=True)

        output_path = os.path.join(output_dir, 'ic_launcher.png')

        # Resize image
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        resized.save(output_path, 'PNG', optimize=True)
        print(f"Generated {density}: {size}x{size} -> {output_path}")

        # Also generate round icon
        output_path_round = os.path.join(output_dir, 'ic_launcher_round.png')
        resized.save(output_path_round, 'PNG', optimize=True)
        print(f"Generated {density} (round): {size}x{size} -> {output_path_round}")

if __name__ == '__main__':
    generate_icons()
    print("\nAll icons generated successfully!")
