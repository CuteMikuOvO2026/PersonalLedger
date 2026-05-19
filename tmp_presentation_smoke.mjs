(async () => {
  const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
  const { Canvas } = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/node_modules/skia-canvas/lib/index.js');
  const { Presentation, PresentationFile, column, text, fill, hug, fixed, rule, drawSlideToCtx } = mod;
  const pres = Presentation.create({ slideSize: { width: 1920, height: 1080 } });
  const slide = pres.slides.add();
  const composeRun = slide.compose(
    column({ name: 'root', width: fill, height: fill, padding: 80, gap: 24 }, [
      text('Hello PersonalLedger', { name: 'title', width: fill, height: hug, style: { fontSize: 60, bold: true, color: '#111827' } }),
      rule({ width: fixed(240), stroke: '#5E7CE2', weight: 6 }),
      text('sample export', { name: 'subtitle', width: fill, height: hug, style: { fontSize: 28, color: '#475569' } })
    ]),
    { frame: { left: 0, top: 0, width: 1920, height: 1080 }, baseUnit: 8 }
  );
  const outPptx = 'D:/ovo/Documents/GitHub/PersonalLedger/output/sample_test.pptx';
  const blob = await PresentationFile.exportPptx(pres);
  await blob.save(outPptx);
  const canvas = new Canvas(1920, 1080);
  const ctx = canvas.getContext('2d');
  await drawSlideToCtx(slide, pres, ctx);
  await canvas.saveAs('D:/ovo/Documents/GitHub/PersonalLedger/output/sample_test.png');
  const layoutBlob = mod.createPresentationLayoutExportBlob(slide, [composeRun]);
  await (await mod.FileBlob.fromBlob(layoutBlob, 'layout.json')).save('D:/ovo/Documents/GitHub/PersonalLedger/output/sample_test.layout.json');
  console.log('ok');
})();
