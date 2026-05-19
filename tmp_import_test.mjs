(async () => {
  const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
  const { Canvas } = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/node_modules/skia-canvas/lib/index.js');
  const pres = await mod.PresentationFile.importPptx('D:/ovo/Documents/GitHub/PersonalLedger/output/sample_test.pptx');
  console.log('slides', pres.slides.items.length);
  const slide = pres.slides.items[0];
  const canvas = new Canvas(1920, 1080);
  const ctx = canvas.getContext('2d');
  await mod.drawSlideToCtx(slide, pres, ctx);
  await canvas.toFile('D:/ovo/Documents/GitHub/PersonalLedger/output/sample_test_imported.png');
  console.log('ok');
})();
