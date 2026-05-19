(async () => {
  const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
  const { Canvas } = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/node_modules/skia-canvas/lib/index.js');
  const { Presentation, layers, shape, text, fill, hug, drawSlideToCtx } = mod;
  const pres = Presentation.create({ slideSize: { width: 400, height: 300 } });
  const slide = pres.slides.add();
  slide.compose(layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: '#112233' }),
    text('Test', { width: hug, height: hug, style: { fontSize: 40, color: '#ffffff', bold: true } })
  ]), { frame: { left:0, top:0, width:400, height:300 }, baseUnit:8 });
  const canvas = new Canvas(400,300);
  const ctx = canvas.getContext('2d');
  await drawSlideToCtx(slide, pres, ctx);
  await canvas.toFile('D:/ovo/Documents/GitHub/PersonalLedger/output/fill_test.png');
  console.log('ok');
})();
