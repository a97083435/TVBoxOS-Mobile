'use strict';
// Local authenticated RPC. Each site has its own cached module and serialized calls.
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {createHash} = require('node:crypto');
const [root, port, token] = process.argv.slice(2);
const sites = new Map();
let queue = Promise.resolve();
async function call(req) {
  const {key, method, args = []} = req;
  if (method === 'ping') return process.versions.node;
  if (method === 'load') {
    const [api, ext] = args;
    let location = api;
    if (/^https?:\/\//.test(api)) {
      const response = await fetch(api, {signal: AbortSignal.timeout(45000)});
      if (!response.ok) throw new Error(`脚本下载失败 HTTP ${response.status}`);
      const source = await response.text();
      if (Buffer.byteLength(source) > 8 * 1024 * 1024) throw new Error('Node.js 脚本过大');
      const suffix = new URL(api).pathname.endsWith('.mjs') ? '.mjs' : '.cjs';
      const directory = path.join(root, 'sites', createHash('sha256').update(key).digest('hex'));
      await fs.mkdir(directory, {recursive:true});
      location = path.join(directory, createHash('sha256').update(source).digest('hex') + suffix);
      await fs.writeFile(location, source);
    } else if (api.startsWith('file://')) location = require('node:url').fileURLToPath(api);
    if (!path.isAbsolute(location)) throw new Error('Node.js 来源需要 HTTP(S) 或本地绝对路径');
    const imported = await import(pathToFileURL(location).href);
    let spider = imported.default || imported;
    if (typeof spider === 'function') spider = await spider();
    const old = sites.get(key); if (old?.destroy) await old.destroy();
    if (spider.init) await spider.init(ext);
    sites.set(key, spider); return true;
  }
  const spider = sites.get(key);
  if (!spider) throw new Error('Node.js 来源已失效，请重新加载来源');
  if (method === 'destroy') { sites.delete(key); if (spider.destroy) await spider.destroy(); return true; }
  const aliases = {homeContent:'home', homeVideoContent:'homeVod', categoryContent:'category', detailContent:'detail', searchContent:'search', playerContent:'play', proxy:'proxy', liveContent:'live', action:'action'};
  const fn = spider[method] || spider[aliases[method]];
  if (typeof fn !== 'function') {
    if (method === 'manualVideoCheck' || method === 'isVideoFormat') return false;
    if (method === 'homeVideoContent') return {list:[]};
    throw new Error(`Node.js 来源未实现 ${method}`);
  }
  const result = await fn.apply(spider, args);
  if (method === 'proxy' && Array.isArray(result) && (Buffer.isBuffer(result[2]) || result[2] instanceof Uint8Array))
    return {status:result[0], mime:result[1], body:Buffer.from(result[2]).toString('base64'), headers:result[3] || {}};
  return result ?? '';
}
http.createServer(async (req, res) => {
  if (req.headers.authorization !== `Bearer ${token}`) { res.writeHead(403); res.end(); return; }
  if (req.method !== 'POST' || req.url !== '/rpc') { res.writeHead(404); res.end(); return; }
  try {
    const chunks = []; let size = 0;
    for await (const chunk of req) { size += chunk.length; if (size > 1024*1024) throw new Error('请求过大'); chunks.push(chunk); }
    const input = JSON.parse(Buffer.concat(chunks));
    const task = queue.then(() => call(input)); queue = task.catch(() => {});
    const result = await task;
    res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.end(JSON.stringify({result}));
  } catch (error) { res.writeHead(500, {'Content-Type':'application/json'}); res.end(JSON.stringify({error:String(error.message || error)})); }
}).listen(Number(port), '127.0.0.1');
