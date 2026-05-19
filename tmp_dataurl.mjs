import fs from 'node:fs';
const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
const { Canvas } = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/node_modules/skia-canvas/lib/index.js');
const imgPath = 'D:/ovo/Documents/GitHub/PersonalLedger/src/presentations/assets/home_card.png';
const b64 = fs.readFileSync(imgPath).toString('base64');
const dataUrl = `data:image/png;base64,${b64}`;
const pres = mod.Presentation.create({ slideSize: { width: 800, height: 450 } });
const slide = pres.slides.add();
slide.compose(mod.layers({ width: mod.fill, height: mod.fill }, [
  mod.shape({ width: mod.fill, height: mod.fill, fill: '#111827' }),
  mod.image({ dataUrl, contentType: 'image/png', width: mod.fill, height: mod.fill, fit: 'contain', alt: 'data' })
]), { frame: { left:0, top:0, width:800, height:450 }, baseUnit:8 });
const canvas = new Canvas(800,450); const ctx = canvas.getContext('2d');
await mod.drawSlideToCtx(slide, pres, ctx);
await canvas.toFile('D:/ovo/Documents/GitHub/PersonalLedger/output/data_url.png');
