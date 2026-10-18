"""Python values over the host SDK; request and resource lifetime remain in the host."""
import json
import base64
from java import dynamic_proxy, jclass


class Local:
    def __init__(self, host):
        self._host = host

    def get(self, key):
        return json.loads(str(self._host.get(key)))

    def set(self, key, value):
        self._host.set(key, json.dumps(value, ensure_ascii=False))

    def delete(self, key):
        self._host.delete(key)


class Net:
    def __init__(self, host):
        self._host = host
        self._callbacks = []

    def req(self, url, options=None):
        return json.loads(str(self._host.req(url, json.dumps(options or {}))))

    http = req

    def json(self, url, options=None):
        return json.loads(str(self._host.json(url, json.dumps(options or {}))))

    def get(self, url, options=None):
        return str(self._host.get(url, json.dumps(options or {})))

    def post(self, url, options=None):
        return str(self._host.post(url, json.dumps(options or {})))

    def session(self):
        return Net(self._host.session())

    def getCookie(self, url):
        return str(self._host.getCookie(url))

    def setCookie(self, url, value):
        self._host.setCookie(url, value)

    def batch(self, requests):
        return json.loads(str(self._host.batch(json.dumps(requests))))

    def cachedText(self, key, options, loader):
        class Load(dynamic_proxy(jclass('com.github.catvod.net.Net$Loader'))):
            def load(self):
                return loader()
        return self._host.cached(key, json.dumps(options or {}), Load())

    def cached(self, key, options, loader):
        value = self.cachedText(key, options, lambda: json.dumps(loader(), ensure_ascii=False))
        return None if value is None else json.loads(str(value))

    def peek(self, key):
        value = self._host.peek(key)
        return None if value is None else json.loads(str(value))

    def refresh(self, key, options, loader):
        class Load(dynamic_proxy(jclass('com.github.catvod.net.Net$Loader'))):
            def load(self):
                return json.dumps(loader(), ensure_ascii=False)
        self._host.refresh(key, json.dumps(options or {}), Load())

    def clearCache(self, key=None):
        if key is None:
            self._host.clearCache()
        else:
            self._host.clearCache(key)

    def download(self, url, path, options=None):
        return str(self._host.download(url, path, json.dumps(options or {})))

    def sleep(self, milliseconds):
        self._host.sleep(milliseconds)

    def ws(self, url, options=None):
        return self._event(str(self._host.ws(url, json.dumps(options or {}))))

    @staticmethod
    def _event(text):
        event = json.loads(text)
        if 'binary' in event:
            event['content'] = base64.b64decode(event.pop('binary'))
        return event

    def connect(self, url, options, callback):
        owner = self
        class Callback(dynamic_proxy(jclass('com.github.catvod.net.Net$SocketCallback'))):
            def event(self, socket, event):
                callback(socket, owner._event(str(event.toJson())))
        bridge = Callback()
        self._callbacks.append(bridge)
        return self._host.connect(url, json.dumps(options or {}), bridge)

    def close(self):
        self._host.close()
        self._callbacks.clear()
