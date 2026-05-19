(async () => {
  const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
  for (const name of ['fill','rule','card']) {
    console.log('\n###'+name);
    console.log(String(mod[name]).slice(0,1200));
  }
})();
