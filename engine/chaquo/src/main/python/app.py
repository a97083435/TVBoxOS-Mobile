import os
import requests
from importlib.machinery import SourceFileLoader
import json
import hashlib
import itertools
import sys

_instances = itertools.count()


def spider(cache, api):
    # Different sites often publish the same spider.py filename. A unique module
    # namespace prevents the second import from replacing the first site's globals.
    identity = 'source_' + hashlib.sha256(api.encode('utf-8')).hexdigest()
    name = identity + '_' + str(next(_instances))
    path = os.path.join(cache, identity + '.py')
    download(path, api)
    try:
        return SourceFileLoader(name, path).load_module().Spider()
    except Exception:
        sys.modules.pop(name, None)
        raise


def download(path, api):
    if api.startswith('http'):
        writeFile(path, redirect(api).content)
    else:
        writeFile(path, str.encode(api))


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url):
    # requests resolves relative Location headers and bounds redirect loops.
    rsp = requests.get(url, allow_redirects=True, verify=False, timeout=(10, 30))
    rsp.raise_for_status()
    return rsp


def str2json(content):
    return json.loads(content)


def getDependence(ru):
    result = ru.getDependence()
    return result


def getName(ru):
    result = ru.getName()
    return result


def init(ru, extend):
    ru.init(extend)


def homeContent(ru, filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def detailContent(ru, array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def searchContent(ru, key, quick, pg="1"):
    result = ru.searchContent(key, quick, pg)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def liveContent(ru, url):
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    result = ru.localProxy(str2json(param))
    return result


def action(ru, action):
    result = ru.action(action)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def destroy(ru):
    try:
        ru.destroy()
    finally:
        name = type(ru).__module__
        if name.startswith('source_'):
            sys.modules.pop(name, None)


def run():
    pass


if __name__ == '__main__':
    run()
