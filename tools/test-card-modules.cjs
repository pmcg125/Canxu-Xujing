// Focused offline contract check for the recovered card modules and Xujing bridge.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../app/src/main/assets');

async function main() {
  const calls = [];
  const books = new Map([['本地', { entries: { 0: { uid: 0, key: ['钥匙'], content: '旧内容' } }, custom: 'retain', error: '资料的普通字段' }]]);
  const host = {
    __XUJING_CONTEXT__: { variables: {}, sessionId: 's', characterId: 'role', worldbookNames: ['本地'], presets: [{id:'p1',name:'预设甲',enabled:true},{id:'p2',name:'预设乙',enabled:true}], activePresetId:'p1', name1: 'User', name2: 'Alice', chat: [{ role: 'user', content: 'hello' }] },
    setTimeout, clearTimeout, DOMException,
    document: { addEventListener() {} },
    XujingBridge: { request(runtimeToken, id, method, payload) {
      const parsed = JSON.parse(payload);
      calls.push({ method, payload: parsed });
      queueMicrotask(() => {
        if (method === 'generate') return host.__xujingReply(id, 'local response');
        // Preset runtime prewarms on load; keep it out of the generation assertions.
        if (method === 'getPresetSettings') return host.__xujingReply(id, { error: '未实现' });
        if (method === 'getPresetList') return host.__xujingReply(id, { presets: [], preset_names: [] });
        if (['createPreset','savePreset','setPreset','replacePreset','updatePreset','deletePreset','renamePreset'].includes(method)) return host.__xujingReply(id, { value: true });
        if (method === 'loadWorldInfo') return host.__xujingReply(id, {value:books.get(parsed.name) ?? null});
        if (method === 'getWorldbook') return host.__xujingReply(id, Object.values(books.get(parsed.name)?.entries || {}));
        if (method === 'loadPreset') {
          const preset=host.__XUJING_CONTEXT__.presets.find(p => p.id===parsed.name || p.name===parsed.name);
          return host.__xujingReply(id, preset ? {id:preset.id,name:preset.name,data:{prompts:[{identifier:'main',content:preset.name}],prompt_order:[{character_id:100001,order:[{identifier:'main',enabled:true}]}]}} : {error:'预设不存在'});
        }
        if (method === 'selectPreset') {
          const preset=host.__XUJING_CONTEXT__.presets.find(p => p.id===parsed.id || p.name===parsed.id);
          const value=!parsed.id || !!preset;
          return host.__xujingReply(id, {value,activePresetId:parsed.id ? preset?.id ?? 'p1' : null});
        }
        if (method === 'saveWorldInfo' && parsed.name === '拒绝') return host.__xujingReply(id, { error: '保存失败' });
        if (method === 'createWorldbook') {
          const created = !books.has(parsed.name);
          if (created) books.set(parsed.name, { entries: {} });
          return host.__xujingReply(id, { value: created, names: [...books.keys()] });
        }
        if (method === 'saveWorldInfo') books.set(parsed.name, parsed.data);
        if (method === 'deleteWorldbook') books.delete(parsed.name);
        host.__xujingReply(id, { value: true, names: [...books.keys()] });
      });
    } },
  };
  host.window = host;
  const context = vm.createContext(host);
  vm.runInContext(fs.readFileSync(path.join(root, 'card-runtime.js'), 'utf8'), context);
  async function load(name) {
    const source = fs.readFileSync(path.join(root, 'card-modules', name), 'utf8');
    const module = new vm.SourceTextModule(source, { context, identifier: name });
    await module.link(() => { throw new Error('Unexpected module dependency'); });
    await module.evaluate();
    return module.namespace;
  }
  const openai = await load('openai.js');
  const utils = await load('utils.js');
  const world = await load('world-info.js');
  const presetModule = await load('preset-manager.js');
  const rootModule = await load('script.js');
  assert.equal(rootModule.streamingProcessor, null);
  const message = await openai.Message.createAsync('user', 'hello', 'm');
  const collection = new openai.MessageCollection('test', message);
  assert.equal(collection.getChat()[0].content, 'hello');
  assert.equal(openai.promptManager.preparePrompt({ content: '{{user}} + {{original}}' }, 'base').content, 'User + base');
  assert.equal(openai.promptManager.messages.getChat()[0].content, 'hello');
  const generates = () => calls.filter(call => call.method === 'generate');
  const response = await openai.sendOpenAIRequest('normal', [{ role: 'user', content: 'question' }]);
  assert.equal(response.choices[0].message.content, 'local response');
  assert.equal(generates().length, 1);
  assert.equal(generates()[0].method, 'generate');
  assert.equal(generates()[0].payload.ordered_prompts[0].content, 'question');
  assert.equal(await utils.getSanitizedFilename('a:b. '), 'a_b');
  assert.equal(utils.equalsIgnoreCaseAndAccents('É', 'e'), true);
  const controller = new AbortController(); controller.abort();
  await assert.rejects(() => openai.sendOpenAIRequest('normal', [], controller.signal), { name: 'AbortError' });
  assert.equal(generates().length, 1);
  assert.deepEqual(Array.from(world.world_names), ['本地']);
  assert.equal((await world.loadWorldInfo('本地')).entries[0].content, '旧内容');
  assert.equal((await world.loadWorldInfo('本地')).error, '资料的普通字段');
  assert.equal(await world.createNewWorldInfo('新书'), true);
  assert.deepEqual(Array.from(world.world_names), ['本地', '新书']);
  assert.equal(await world.createNewWorldInfo('新书'), false);
  const data = await world.loadWorldInfo('新书');
  const entry = world.createWorldInfoEntry('新书', data);
  entry.content = '持久内容'; entry.key = ['星星'];
  await world.saveWorldInfo('新书', data);
  assert.equal((await world.loadWorldInfo('新书')).entries[0].content, '持久内容');
  assert.equal((await host.TavernHelper.getWorldbook('新书'))[0].content, '持久内容');
  await world.charUpdatePrimaryWorld('新书');
  assert.equal(calls.filter(call => call.method !== 'getPresetSettings' && call.method !== 'getPresetList').at(-1).payload.characterId, 'role');
  await assert.rejects(() => world.saveWorldInfo('拒绝', { entries: {} }), /保存失败/);
  assert.equal(await world.deleteWorldInfo('新书'), true);
  assert.deepEqual(Array.from(world.world_names), ['本地']);
  const manager=presetModule.getPresetManager('openai');
  // PiuPiu semantics: current preset name is duplicated at the front of getAllPresets().
  assert.deepEqual(Array.from(manager.getAllPresets()), ['预设甲','预设甲','预设乙']);
  assert.equal(manager.getSelectedPresetName(),'预设甲');
  assert.throws(() => manager.getPresetSettings(), /尚未加载/);
  await manager.loadPreset('预设甲');
  assert.equal(manager.getPresetSettings().prompts[0].content,'预设甲');
  assert.equal(await manager.setActivePresetId('预设乙'), true);
  assert.equal(manager.getActivePresetId(),'预设乙');
  await manager.loadPreset('in_use');
  assert.equal(host.TavernHelper.getPreset().prompts[0].content,'预设乙');
  assert.equal(await manager.setActivePresetId('不存在'), false);
  assert.equal(manager.getActivePresetId(),'预设乙');
  assert.equal(await manager.setActivePresetId(null), true);
  assert.equal(manager.getActivePresetId(),null);
  assert.equal(await manager.setActivePresetId('p1'), true);
  await manager.loadPreset('预设甲');
  host.__xujingUpdate({...host.__XUJING_CONTEXT__,activePresetId:'p1',presets:[{id:'p1',name:'预设甲',enabled:true,revision:2}]});
  assert.throws(() => manager.getPresetSettings(), /尚未加载/);
  console.log('Card module checks passed: generation, worldbook CRUD/binding and preset load/select.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
