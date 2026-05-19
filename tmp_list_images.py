from PIL import Image
from pathlib import Path
root = Path(r"D:\ovo\Documents\GitHub\PersonalLedger\scratch\docx_media\王禹鑫-202251280-基于Kotlin与Jetpack架构的个人账本App的设计与实现")
for path in sorted(root.glob('*')):
    try:
        with Image.open(path) as im:
            print(path.name, im.size, im.mode)
    except Exception as e:
        print(path.name, 'ERR', e)
