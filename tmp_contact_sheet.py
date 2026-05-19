from PIL import Image, ImageOps, ImageDraw
from pathlib import Path
root = Path(r"D:\ovo\Documents\GitHub\PersonalLedger\scratch\docx_media\王禹鑫-202251280-基于Kotlin与Jetpack架构的个人账本App的设计与实现")
paths = sorted(root.glob('*'))
thumb_w, thumb_h = 320, 220
margin = 20
cols = 3
rows = (len(paths) + cols - 1)//cols
sheet = Image.new('RGB', (cols*(thumb_w+margin)+margin, rows*(thumb_h+60+margin)+margin), 'white')
draw = ImageDraw.Draw(sheet)
for idx, path in enumerate(paths):
    with Image.open(path) as im:
        thumb = ImageOps.contain(im.convert('RGB'), (thumb_w, thumb_h))
    x = margin + (idx % cols) * (thumb_w + margin)
    y = margin + (idx // cols) * (thumb_h + 60 + margin)
    sheet.paste(thumb, (x + (thumb_w-thumb.width)//2, y + (thumb_h-thumb.height)//2))
    draw.rectangle([x, y, x+thumb_w, y+thumb_h], outline=(180,180,180), width=1)
    draw.text((x, y+thumb_h+10), path.name, fill='black')
out = root / 'contact_sheet.jpg'
sheet.save(out, quality=90)
print(out)
