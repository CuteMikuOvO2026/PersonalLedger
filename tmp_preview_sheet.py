from PIL import Image, ImageOps, ImageDraw
from pathlib import Path
root = Path(r"D:\ovo\Documents\GitHub\PersonalLedger\output\presentations\personalledger\previews")
paths = sorted(root.glob('slide-*.png'))
thumb_w, thumb_h = 320, 180
margin = 18
cols = 2
rows = (len(paths) + cols - 1)//cols
sheet = Image.new('RGB', (cols*(thumb_w+margin)+margin, rows*(thumb_h+52+margin)+margin), '#0f1115')
draw = ImageDraw.Draw(sheet)
for idx, path in enumerate(paths):
    with Image.open(path) as im:
        thumb = ImageOps.contain(im.convert('RGB'), (thumb_w, thumb_h))
    x = margin + (idx % cols) * (thumb_w + margin)
    y = margin + (idx // cols) * (thumb_h + 52 + margin)
    sheet.paste(thumb, (x + (thumb_w-thumb.width)//2, y + (thumb_h-thumb.height)//2))
    draw.rectangle([x, y, x+thumb_w, y+thumb_h], outline=(80,88,104), width=1)
    draw.text((x, y+thumb_h+8), path.stem, fill='white')
out = root / 'contact_sheet.jpg'
sheet.save(out, quality=92)
print(out)
