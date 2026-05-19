(async () => {
  const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
  for (const name of ['drawSlideToCtx','createPresentationLayoutExportBlob','buildPresentationLayoutExport','Presentation']) {
    const fn = mod[name];
    console.log('\n###', name);
    console.log(String(fn).slice(0,1200));
  }
})();
