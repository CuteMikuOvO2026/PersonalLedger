(async () => {
  const mod = await import('file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs');
  for (const name of ['panel','shape','text','image','chart','row','column','grid','layers']) {
    console.log('\n###'+name);
    console.log(String(mod[name]).slice(0,1000));
  }
})();
