from docx import Document
paths = [r"C:\Users\ovo\Desktop\1\PersonalLedger_项目总结.docx", r"C:\Users\ovo\Desktop\1\王禹鑫-202251280-基于Kotlin与Jetpack架构的个人账本App的设计与实现.docx"]
for path in paths:
    print(f"\n###FILE### {path}")
    doc = Document(path)
    for p in doc.paragraphs[:260]:
        text = p.text.strip()
        if text:
            print(text)
