import * as spider from '%s'

if (!globalThis.__JS_SPIDER__) {
    if (spider.__jsEvalReturn) {
        globalThis.req = http
        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()
    } else if (spider.default) {
        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default
    }
}

// Plain objects and async object results use the same wire format as JSON strings.
const instance = globalThis.__JS_SPIDER__;
if (instance) {
    const encode = value => value != null && typeof value === 'object' ? JSON.stringify(value) : value;
    for (const name of ['home', 'homeVod', 'category', 'detail', 'search', 'play', 'live', 'action']) {
        const method = instance[name];
        if (typeof method !== 'function') continue;
        instance[name] = function (...args) {
            const result = method.apply(this, args);
            return result && typeof result.then === 'function' ? result.then(encode) : encode(result);
        };
    }
}
