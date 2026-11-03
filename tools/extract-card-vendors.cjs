// Decode only string literals and selected library declarations, never execute the application chunk.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const source = process.argv[2];
if (!source) throw Error('Pass the recovered formatted GroupMemberSelector JavaScript path');
const text = fs.readFileSync(source, 'utf8');
const out = path.resolve(__dirname, '../app/src/main/assets/card-vendors');
fs.mkdirSync(out, { recursive: true });
function literal(name) {
  const match = new RegExp('\\b' + name + '\\s*=\\s*([\x27\x22])').exec(text);
  if (!match) throw Error('Missing literal ' + name);
  return decodeAt(match.index + match[0].length - 1);
}
function decodeAt(start) {
  const quote = text[start];
  let end = start + 1;
  for (; end < text.length; end++) {
    if (text[end] === '\\') { end++; continue; }
    if (text[end] === quote) break;
  }
  if (end === text.length) throw Error('Unclosed literal');
  const value = vm.runInNewContext(text.slice(start, end + 1), Object.create(null), { timeout: 1000 });
  if (typeof value !== 'string') throw Error('Not a string');
  return value;
}
const zod = literal('Wc');
function containing(term) {
  const offset = text.indexOf(term);
  if (offset < 0) throw Error('Missing runtime fragment ' + term);
  const line = text.lastIndexOf('\n', offset) + 1;
  const quote = text.slice(line).search(/[\x22\x27]/);
  return decodeAt(line + quote);
}
const utils = containing('const __isPlainObject =');
const mvuSource = containing('const __mvuEvents =');
const mvu = `/* Extracted PiuPiu 2.7.9 MVU helpers and schema hooks; host adapters below. */
(() => {
const __clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const eventSource = window.eventSource;
const __rpc = (method, args) => Promise.reject(Error('MVU host method not yet available: ' + method));
${utils.slice(utils.indexOf('const __isPlainObject ='), utils.indexOf('const __notifyListeners'))}
${mvuSource.slice(mvuSource.indexOf('const __mvuEvents ='), mvuSource.indexOf('function __activeMessageVariables'))}
${mvuSource.slice(mvuSource.indexOf('const __mvu ='), mvuSource.indexOf('let __rpcCounter'))}
})();
`;
const utilityStart = text.indexOf('function Jc()');
const endMarker = 'eu = dt(Qc)';
const end = text.indexOf(endMarker, utilityStart);
if (utilityStart < 0 || end < 0) throw Error('Missing EJS declarations');
// Browser rendering needs no filesystem. EJS's original renderFile/include without an
// explicit includer fails clearly rather than reaching Android files.
const ejs = `/* Recovered EJS 3.1.10, Apache-2.0. See THIRD_PARTY_NOTICES.md. */
(() => {
var Dc, Zc = {}, Kc = {};
var $c = {
  resolve: (...parts) => new URL(parts.join('/'), 'https://cards.xujing.invalid/').pathname,
  dirname: value => String(value).replace(/[^/]*$/, ''),
  extname: value => /\\.[^/.]*$/.exec(String(value))?.[0] || '',
  existsSync: () => false,
  readFileSync: () => { throw Error('File templates are unavailable; use an explicit EJS includer'); }
};
${text.slice(utilityStart, end)} unused = null;
window.ejs = Qc;
})();
`;
for (const [name, body] of Object.entries({ 'zod.js': zod, 'ejs.js': ejs, 'mvu.js': mvu })) {
  new vm.Script(body, { filename: name });
  fs.writeFileSync(path.join(out, name), body);
}
fs.writeFileSync(path.join(out, 'provenance.json'), JSON.stringify({
  sourceFile: path.basename(source), sourceSha256: crypto.createHash('sha256').update(fs.readFileSync(source)).digest('hex'),
  files: ['zod.js', 'ejs.js', 'mvu.js'].map(name => ({ name, sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(out, name))).digest('hex') })),
  extraction: 'Wc literal; Jc/EJS declarations with browser IO adapter; MVU helpers and schema hooks from decoded runtime literals',
}, null, 2) + '\n');
console.log('Extracted Zod and EJS library assets');
