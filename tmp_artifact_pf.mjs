(async () => {
  const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
  const PF = mod.PresentationFile;
  console.log('PresentationFile keys:', Object.getOwnPropertyNames(PF));
  console.log('prototype keys:', Object.getOwnPropertyNames(PF.prototype));
})();
