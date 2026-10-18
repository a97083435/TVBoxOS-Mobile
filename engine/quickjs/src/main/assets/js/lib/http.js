let req = (url, options) => http(url, Object.assign({
    async: false
}, options));

function http(url, options = {}) {
    if (options?.async === false) return _http(url, options)
    return new Promise(resolve => _http(url, Object.assign({
        complete: res => resolve(res)
    }, options))).catch(err => {
        console.error(err.name, err.message, err.stack)
        return {
            ok: false,
            status: 500,
            url
        }
    })
}

function defineGlobalAlias(name) {
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, name);
    if (descriptor && !descriptor.configurable) return;
    Object.defineProperty(globalThis, name, {
        enumerable: true,
        configurable: true,
        get() {
            return globalThis;
        },
        set() {}
    });
}

['global', 'window', 'self'].forEach(defineGlobalAlias);

// Available before source modules and factories are evaluated.
globalThis.page = function (items, pg, size = 20) {
    const list = Array.isArray(items) ? items : [];
    const current = Math.max(1, Math.trunc(Number(pg)) || 1);
    const limit = Math.max(1, Math.trunc(Number(size)) || 20);
    return {page: current, pagecount: Math.ceil(list.length / limit), limit, total: list.length,
        list: list.slice((current - 1) * limit, current * limit)};
};
