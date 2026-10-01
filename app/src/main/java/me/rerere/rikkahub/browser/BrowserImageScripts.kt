package me.rerere.rikkahub.browser

/** Fixed scripts; URL and session key are encoded as JSON string literals. No native JS bridge. */
internal object BrowserImageScripts {
    /** Retain the page's own download Blob; fetching a Blob URL can be rejected by the webpage. */
    fun install(key: String): String = """
        (() => {
          const key = ${browserJsString(key)};
          if (window !== top || location.origin !== 'https://chatgpt.com') return JSON.stringify({status:'error'});
          if (window[key]) return JSON.stringify({status:'installed'});
          const state = {entry:null, created:new Map(), retainedBytes:0, manualUntil:0, aiUntil:0};
          const dropCreated = source => {
            const value = state.created.get(source);
            if (value) { clearTimeout(value.timer); state.retainedBytes -= value.blob.size; state.created.delete(source); }
          };
          const clearCreated = () => { for (const source of Array.from(state.created.keys())) dropCreated(source); };
          const clear = () => {
            if (state.entry) { state.entry.controller.abort(); clearTimeout(state.entry.timer); state.entry = null; }
            clearCreated();
            state.manualUntil = 0; state.aiUntil = 0;
          };
          const originalCreate = URL.createObjectURL;
          const wrappedCreate = function(blob) {
            const source = originalCreate.apply(this, arguments);
            const now = Date.now();
            // Capture only a recent permitted download action. Keep at most 4 objects / 20 MiB total.
            if (location.origin === 'https://chatgpt.com' && !state.entry &&
                (now <= state.manualUntil || now <= state.aiUntil) && blob instanceof Blob &&
                blob.size > 0 && blob.size <= $MAX_BROWSER_IMAGE_BYTES) {
              dropCreated(source);
              while (state.created.size >= 4 || state.retainedBytes + blob.size > $MAX_BROWSER_IMAGE_BYTES) {
                dropCreated(state.created.keys().next().value);
              }
              const value = {blob};
              value.timer = setTimeout(() => { if (state.created.get(source) === value) dropCreated(source); }, 45000);
              state.created.set(source, value); state.retainedBytes += blob.size;
            }
            return source;
          };
          if (typeof originalCreate === 'function') URL.createObjectURL = wrappedCreate;
          const capture = anchor => {
            if (location.origin !== 'https://chatgpt.com' || !(anchor instanceof HTMLAnchorElement) || !anchor.hasAttribute('download')) return;
            const now = Date.now(), source = anchor.href;
            if (now > state.manualUntil && now > state.aiUntil) return;
            try {
              if (!source.startsWith('blob:')) return;
              const u = new URL(source.slice(5));
              if (u.origin !== location.origin || u.username || u.password) return;
            } catch (_) { return; }
            if (state.entry && state.entry.source === source && now < state.entry.expires) return;
            if (state.entry) { state.entry.controller.abort(); clearTimeout(state.entry.timer); }
            const retained = state.created.get(source)?.blob;
            clearCreated(); // Once a download begins, the one pending entry owns the cache budget.
            state.aiUntil = 0;
            const entry = {source, transport:retained ? 'retained_blob' : 'cached_blob', controller:new AbortController(), expires:now + 45000};
            state.entry = entry;
            entry.timer = setTimeout(() => { entry.controller.abort(); if (state.entry === entry) state.entry = null; }, 45000);
            entry.promise = (async () => {
              try {
                if (retained) {
                  const mime = retained.type.split(';')[0].trim().toLowerCase();
                  if (!['image/png','image/jpeg','image/webp'].includes(mime)) throw 'type';
                  // Same MIME normalization as the fetch path, without fetching an object URL.
                  return retained.type === mime ? retained : retained.slice(0, retained.size, mime);
                }
                // Older page-created URLs can still use the existing browser fetch path.
                const response = await fetch(source, {credentials:'same-origin', redirect:'error', referrerPolicy:'no-referrer', signal:entry.controller.signal});
                if (!response.ok) throw 'http';
                const mime = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
                if (!['image/png','image/jpeg','image/webp'].includes(mime)) throw 'type';
                if (Number(response.headers.get('Content-Length')) > $MAX_BROWSER_IMAGE_BYTES) throw 'size';
                if (!response.body || !response.body.getReader) throw 'unsupported';
                const reader = response.body.getReader(), parts = [];
                let size = 0;
                while (true) {
                  const chunk = await reader.read();
                  if (chunk.done) break;
                  size += chunk.value.byteLength;
                  if (size > $MAX_BROWSER_IMAGE_BYTES) throw 'size';
                  parts.push(chunk.value);
                }
                if (!size || entry.controller.signal.aborted) throw 'cancelled';
                return new Blob(parts, {type:mime});
              } catch (error) { entry.controller.abort(); throw error; }
            })();
            entry.promise.catch(() => {}); // The native consumer receives the original failure safely.
          };
          const intent = event => { if (event.isTrusted) state.manualUntil = Date.now() + $IMAGE_DOWNLOAD_ACTION_TTL_MS; };
          const click = event => { intent(event); capture(event.target && event.target.closest ? event.target.closest('a[download]') : null); };
          const original = HTMLAnchorElement.prototype.click;
          const wrapped = function() { capture(this); return original.apply(this, arguments); };
          HTMLAnchorElement.prototype.click = wrapped;
          document.addEventListener('pointerdown', intent, true);
          document.addEventListener('click', click, true);
          window[key] = {
            arm:() => { state.aiUntil = Date.now() + $IMAGE_DOWNLOAD_ACTION_TTL_MS; },
            clear,
            take:source => {
              const entry = state.entry;
              if (!entry || entry.source !== source || Date.now() >= entry.expires) return null;
              state.entry = null; state.manualUntil = 0; state.aiUntil = 0; clearTimeout(entry.timer);
              return entry;
            },
            dispose:() => {
              clear();
              document.removeEventListener('pointerdown', intent, true);
              document.removeEventListener('click', click, true);
              if (HTMLAnchorElement.prototype.click === wrapped) HTMLAnchorElement.prototype.click = original;
              if (URL.createObjectURL === wrappedCreate) URL.createObjectURL = originalCreate;
              delete window[key];
            }
          };
          return JSON.stringify({status:'installed'});
        })()
    """.trimIndent()

    fun arm(key: String): String = """
        (() => { const c=window[${browserJsString(key)}]; if(c && window===top && location.origin==='https://chatgpt.com') { c.arm(); return JSON.stringify({status:'armed'}); } return JSON.stringify({status:'error'}); })()
    """.trimIndent()

    fun clear(key: String): String = """
        (() => { const c=window[${browserJsString(key)}]; if(c) c.clear(); return JSON.stringify({status:'cleared'}); })()
    """.trimIndent()

    fun uninstall(key: String): String = """
        (() => { const c=window[${browserJsString(key)}]; if(c) c.dispose(); return JSON.stringify({status:'removed'}); })()
    """.trimIndent()

    fun begin(key: String, source: String, cacheKey: String? = null): String = """
        (() => {
          const key = ${browserJsString(key)}, source = ${browserJsString(source)}, cacheKey = ${cacheKey?.let(::browserJsString) ?: "null"};
          const fail = code => JSON.stringify({status:'error', code});
          if (window !== top || location.origin !== 'https://chatgpt.com') return fail('origin');
          const allowed = value => {
            try {
              const u = new URL(value.startsWith('blob:') ? value.slice(5) : value);
              if (u.protocol !== 'https:' || u.username || u.password || (u.port && u.port !== '443')) return false;
              if (value.startsWith('blob:')) return u.hostname === 'chatgpt.com';
              return u.hostname === 'chatgpt.com' || u.hostname === 'oaiusercontent.com' || u.hostname.endsWith('.oaiusercontent.com');
            } catch (_) { return false; }
          };
          if (!allowed(source)) return fail('origin');
          if (window[key]) return fail('busy');
          const s = {status:'pending', offset:0, transport:source.startsWith('blob:') ? 'blob' : 'https', controller:new AbortController()};
          window[key] = s;
          const timer = setTimeout(() => s.controller.abort(), 45000);
          (async () => {
            try {
              const cache = cacheKey && window[cacheKey], cached = cache && cache.take(source);
              if (cached) {
                s.transport = cached.transport || 'cached_blob';
                s.controller.abort(); s.controller = cached.controller;
                const blob = await cached.promise;
                if (window[key] !== s || s.controller.signal.aborted) return;
                s.blob = blob; s.mime = blob.type; s.total = blob.size; s.status = 'ready';
                return;
              }
              const sameOrigin = source.startsWith('blob:') || new URL(source).origin === location.origin;
              const response = await fetch(source, {
                credentials:sameOrigin ? 'same-origin' : 'omit', redirect:'error',
                referrerPolicy:'no-referrer', signal:s.controller.signal
              });
              if (!response.ok) throw 'http';
              const mime = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
              if (!['image/png','image/jpeg','image/webp'].includes(mime)) throw 'type';
              const length = Number(response.headers.get('Content-Length'));
              if (length > $MAX_BROWSER_IMAGE_BYTES) throw 'size';
              if (!response.body || !response.body.getReader) throw 'unsupported';
              const reader = response.body.getReader(), parts = [];
              let total = 0;
              while (true) {
                const chunk = await reader.read();
                if (chunk.done) break;
                total += chunk.value.byteLength;
                if (total > $MAX_BROWSER_IMAGE_BYTES) { s.controller.abort(); throw 'size'; }
                parts.push(chunk.value);
              }
              if (!total) throw 'empty';
              if (window[key] !== s || s.controller.signal.aborted) return;
              s.blob = new Blob(parts, {type:mime}); s.mime = mime; s.total = total; s.status = 'ready';
            } catch (error) {
              s.controller.abort();
              if (window[key] === s) { s.status = 'error'; s.code = typeof error === 'string' ? error : 'fetch'; }
            } finally { clearTimeout(timer); }
          })();
          return JSON.stringify({status:'pending'});
        })()
    """.trimIndent()

    fun poll(key: String): String = """
        (() => {
          const s = window[${browserJsString(key)}];
          if (!s || window !== top || location.origin !== 'https://chatgpt.com') return JSON.stringify({status:'error',code:'cancelled'});
          if (s.status === 'error') return JSON.stringify({status:'error',code:s.code,transport:s.transport});
          if (s.status === 'chunk') {
            const result = {status:'chunk', offset:s.offset, total:s.total, mime:s.mime, data:s.data};
            s.offset += s.chunkSize; s.data = null; s.status = 'ready';
            return JSON.stringify(result);
          }
          if (s.status === 'ready') {
            if (s.offset === s.total) return JSON.stringify({status:'complete',total:s.total});
            const end = Math.min(s.offset + $BROWSER_IMAGE_CHUNK_BYTES, s.total);
            const reader = new FileReader(); s.reader = reader; s.chunkSize = end - s.offset; s.status = 'reading';
            reader.onload = () => {
              if (!s.controller.signal.aborted) {
                const value = reader.result;
                if (typeof value !== 'string' || !value.includes(';base64,')) { s.status='error'; s.code='read'; return; }
                s.data = value.slice(value.indexOf(',') + 1); s.status = 'chunk';
              }
            };
            reader.onerror = () => { s.status='error'; s.code='read'; };
            reader.readAsDataURL(s.blob.slice(s.offset, end, s.mime));
          }
          return JSON.stringify({status:'pending'});
        })()
    """.trimIndent()

    fun cancel(key: String): String = """
        (() => {
          const key = ${browserJsString(key)}, s = window[key];
          if (s) { s.controller.abort(); if (s.reader && s.reader.readyState === 1) s.reader.abort(); delete window[key]; }
          return JSON.stringify({status:'cancelled'});
        })()
    """.trimIndent()
}
