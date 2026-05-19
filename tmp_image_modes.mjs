import path from 'node:path';
import { pathToFileURL } from 'node:url';
const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
const { Canvas } = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/node_modules/skia-canvas/lib/index.js');
const imgPath = 'D:/ovo/Documents/GitHub/PersonalLedger/src/presentations/assets/home_card.png';
const fileUrl = pathToFileURL(imgPath).href;
for (const [name, spec] of Object.entries({
  path_plain: { path: imgPath },
  path_fileurl: { path: fileUrl },
  uri_fileurl: { uri: fileUrl }
})) {
  const pres = mod.Presentation.create({ slideSize: { width: 800, height: 450 } });
  const slide = pres.slides.add();
  slide.compose(mod.layers({ width: mod.fill, height: mod.fill }, [
    mod.shape({ width: mod.fill, height: mod.fill, fill: '#111827' }),
    mod.image({ ...spec, width: mod.fill, height: mod.fill, fit: 'contain', alt: name })
  ]), { frame: { left: 0, top: 0, width: 800, height: 450 }, baseUnit: 8 });
  const canvas = new Canvas(800, 450); const ctx = canvas.getContext('2d');
  await mod.drawSlideToCtx(slide, pres, ctx);
  await canvas.toFile(`D:/ovo/Documents/GitHub/PersonalLedger/output/${name}.png`);
}
