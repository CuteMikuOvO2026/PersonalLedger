from docx import Document
from pathlib import Path
from zipfile import ZipFile
files = [r"C:\Users\ovo\Desktop\1\王禹鑫-202251280-基于Kotlin与Jetpack架构的个人账本App的设计与实现.docx", r"C:\Users\ovo\Desktop\1\PersonalLedger_项目总结.docx"]
out_root = Path(r"D:\ovo\Documents\GitHub\PersonalLedger\scratch\docx_media")
out_root.mkdir(parents=True, exist_ok=True)
for src in files:
    src_path = Path(src)
    target = out_root / src_path.stem
    target.mkdir(parents=True, exist_ok=True)
    with ZipFile(src_path) as zf:
        media = [n for n in zf.namelist() if n.startswith('word/media/')]
        for name in media:
            dest = target / Path(name).name
            dest.write_bytes(zf.read(name))
        print(src_path.name, len(media), 'images', target)
