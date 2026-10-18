// Source-scoped SDK. The original local(rule, key) bridge remains available as overloads.
(() => {
    globalThis.link = (name, target) => '<a href="catvod://' + encodeURIComponent(JSON.stringify(target)) + '">' + String(name).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char])) + '</a>';
    const legacyLocal = globalThis.local;
    const decode = value => value == null ? null : JSON.parse(value);
    globalThis.local = {
        get(...args) { return args.length > 1 ? legacyLocal.get(...args) : decode(_sourceLocal('get', args[0])); },
        set(...args) { return args.length > 2 ? legacyLocal.set(...args) : _sourceLocal('set', args[0], JSON.stringify(args[1])); },
        delete(...args) { return args.length > 1 ? legacyLocal.delete(...args) : _sourceLocal('delete', args[0]); }
    };
    const unpack = text => { const result = JSON.parse(text); if (!result.ok) throw new Error(result.error); return result.value; };
    const jsonMethods = new Set(['req', 'http', 'json', 'batch', 'ws', 'send']);
    function make(id, persist = true) {
        const cache = new Map(), loads = new Map(), generations = new Map(), children = new Set();
        let closed = false, epoch = 0;
        const check = () => { if (closed) throw new Error('来源网络已关闭'); };
        const sync = (method, args) => { check(); const value = unpack(_sourceNet(id, method, JSON.stringify(args), null)); return jsonMethods.has(method) ? decode(value) : value; };
        const async = (method, args) => { check(); return new Promise((resolve, reject) => _sourceNet(id, method, JSON.stringify(args), text => {
            try { const value = unpack(text); resolve(jsonMethods.has(method) ? decode(value) : value); } catch (error) { reject(error); }
        })); };
        const storedKey = key => '__net_cache_' + key;
        const entry = key => {
            if (!cache.has(key) && persist) { const value = local.get(storedKey(key)); if (value) cache.set(key, value); }
            return cache.get(key);
        };
        const load = (key, options, loader) => {
            check(); if (loads.has(key)) return loads.get(key);
            const generation = generations.get(key), all = epoch;
            let cancel;
            const operation = new Promise((resolve, reject) => {
                cancel = () => reject(new Error('缓存载入已取消'));
                Promise.resolve().then(loader).then(value => {
                    if (!closed && all === epoch && generation === generations.get(key)) {
                        const ready = {value, expires: Date.now() + Math.max(0, options.ttl ?? 60000)};
                        cache.set(key, ready); if (persist && options.persist) {
                            const keys = local.get('__net_cache_keys') || [];
                            if (!keys.includes(key)) local.set('__net_cache_keys', [...keys, key]);
                            local.set(storedKey(key), ready);
                        }
                    }
                    resolve(value);
                }).catch(reject);
            });
            const pending = operation.finally(() => { if (loads.get(key) === pending) loads.delete(key); });
            pending.cancel = cancel;
            loads.set(key, pending); return pending;
        };
        const api = {
            req: (url, options = {}) => sync('req', [url, options]),
            http: (url, options = {}) => async('http', [url, options]),
            json: (url, options = {}) => async('json', [url, options]),
            get: (url, options = {}) => async('get', [url, options]),
            post: (url, options = {}) => async('post', [url, options]),
            getCookie: url => sync('getCookie', [url]),
            setCookie: (url, value) => sync('setCookie', [url, value]),
            batch: requests => async('batch', [requests]),
            download: (url, file, options = {}) => async('download', [url, file, options]),
            sleep: milliseconds => async('sleep', [milliseconds]),
            ws: (url, options = {}) => async('ws', [url, options]).then(binary),
            session() { const child = make(sync('session', []), false); children.add(child); return child; },
            peek: key => entry(key)?.value ?? null,
            cached(key, options, loader) {
                check(); const value = entry(key);
                if (value && value.expires > Date.now()) return Promise.resolve(value.value);
                if (value && options.stale) { load(key, options, loader).catch(() => {}); return Promise.resolve(value.value); }
                return load(key, options, loader);
            },
            refresh(key, options, loader) { if (!entry(key) || entry(key).expires <= Date.now()) load(key, options, loader).catch(() => {}); },
            clearCache(key) {
                if (key === undefined) { epoch++; if (persist) { for (const item of new Set([...cache.keys(), ...(local.get('__net_cache_keys') || [])])) local.delete(storedKey(item)); local.delete('__net_cache_keys'); } for (const load of loads.values()) load.cancel(); cache.clear(); loads.clear(); }
                else { generations.set(key, (generations.get(key) || 0) + 1); cache.delete(key); loads.get(key)?.cancel(); loads.delete(key); if (persist) local.delete(storedKey(key)); }
            },
            connect(url, options, callback) {
                check(); const socket = {send: value => syncSocket('send', value instanceof Uint8Array ? Array.from(value) : value), close: () => syncSocket('socketClose')};
                let socketId, stopped = false;
                socket.close = () => { stopped = true; return syncSocket('socketClose'); };
                const syncSocket = (method, ...args) => { if (!socketId) return false; return unpack(_sourceNet(socketId, method, JSON.stringify(args), null)); };
                _sourceNet(id, 'connect', JSON.stringify([url, options || {}]), text => { const event = binary(JSON.parse(text)); socketId = event.socket; if (stopped && event.type === 'open') { syncSocket('socketClose'); return; } callback(socket, event); });
                return socket;
            },
            close() { if (closed) return; for (const child of children) child.close(); children.clear(); sync('close', []); closed = true; epoch++; for (const load of loads.values()) load.cancel(); cache.clear(); loads.clear(); }
        };
        return api;
    }
    function binary(event) {
        if (event.binary) { const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'; let bits = 0, value = 0; const bytes = []; for (const char of event.binary) { const digit = alphabet.indexOf(char); if (digit < 0) continue; value = (value << 6) | digit; bits += 6; if (bits >= 8) { bits -= 8; bytes.push((value >> bits) & 255); } } event.content = Uint8Array.from(bytes); delete event.binary; }
        return event;
    }
    globalThis.net = make('root');
})();
