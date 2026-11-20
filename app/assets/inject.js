(function() {
  'use strict';

  // 画面上に起動ボタンを追加
  const launchBtn = document.createElement('button');
  launchBtn.textContent = '🗑 発言削除ツールを開く';
  launchBtn.style.cssText = 'position:fixed;bottom:12.5vh;right:20px;z-index:999998;background:#5865f2;color:#fff;border:none;padding:12px 16px;border-radius:24px;font-size:14px;box-shadow:0 2px 10px rgba(0,0,0,.4);touch-action:none;';
  document.body.appendChild(launchBtn);

  // --- 長押しでドラッグ移動できるようにする(位置はlocalStorageに保存して次回起動時も維持) ---
  (function makeDraggable(){
    const POS_KEY = '__dcBtnPos';
    const LONG_PRESS_MS = 350;
    const MOVE_CANCEL_PX = 10;

    let longPressTimer = null;
    let dragging = false;
    let moved = false;
    let startX = 0, startY = 0, startLeft = 0, startTop = 0;

    function clampAndApply(left, top){
      const rect = launchBtn.getBoundingClientRect();
      const maxLeft = Math.max(0, window.innerWidth - rect.width);
      const maxTop = Math.max(0, window.innerHeight - rect.height);
      left = Math.min(Math.max(0, left), maxLeft);
      top = Math.min(Math.max(0, top), maxTop);
      launchBtn.style.left = left + 'px';
      launchBtn.style.top = top + 'px';
      launchBtn.style.right = 'auto';
      launchBtn.style.bottom = 'auto';
      return { left, top };
    }

    try {
      const saved = JSON.parse(localStorage.getItem(POS_KEY) || 'null');
      if (saved && typeof saved.left === 'number' && typeof saved.top === 'number') {
        clampAndApply(saved.left, saved.top);
      }
    } catch(e){}

    window.addEventListener('resize', () => {
      const rect = launchBtn.getBoundingClientRect();
      clampAndApply(rect.left, rect.top);
    });

    launchBtn.addEventListener('touchstart', (e) => {
      if (e.touches.length !== 1) return;
      const t = e.touches[0];
      startX = t.clientX; startY = t.clientY;
      const rect = launchBtn.getBoundingClientRect();
      startLeft = rect.left; startTop = rect.top;
      moved = false;
      dragging = false;
      clearTimeout(longPressTimer);
      longPressTimer = setTimeout(() => {
        dragging = true;
        launchBtn.style.opacity = '0.6';
        if (navigator.vibrate) { try { navigator.vibrate(15); } catch(e){} }
      }, LONG_PRESS_MS);
    }, { passive: true });

    launchBtn.addEventListener('touchmove', (e) => {
      if (e.touches.length !== 1) return;
      const t = e.touches[0];
      const dx = t.clientX - startX;
      const dy = t.clientY - startY;
      if (!dragging) {
        if (Math.abs(dx) > MOVE_CANCEL_PX || Math.abs(dy) > MOVE_CANCEL_PX) {
          clearTimeout(longPressTimer);
        }
        return;
      }
      moved = true;
      e.preventDefault();
      clampAndApply(startLeft + dx, startTop + dy);
    }, { passive: false });

    function onTouchEnd(){
      clearTimeout(longPressTimer);
      if (dragging) {
        launchBtn.style.opacity = '1';
        const rect = launchBtn.getBoundingClientRect();
        try { localStorage.setItem(POS_KEY, JSON.stringify({ left: rect.left, top: rect.top })); } catch(e){}
      }
      setTimeout(() => { dragging = false; moved = false; }, 50);
    }
    launchBtn.addEventListener('touchend', onTouchEnd);
    launchBtn.addEventListener('touchcancel', onTouchEnd);

    launchBtn.onclick = (e) => {
      if (moved) { e.preventDefault(); e.stopPropagation(); return; }
      runTool();
    };
  })();

  function runTool(){
  if (window.__dcDeleterOpen) { alert('既に起動しています'); return; }
  window.__dcDeleterOpen = true;

  try {

  function getToken(){
    const isValid = (t) => typeof t === 'string' && t.length > 20 && t.length < 200;

    // 方法1: webpackモジュールから取得(AuthenticationStoreらしきものに限定して誤検出を防ぐ)
    try {
      const chunkVarNames = Object.keys(window).filter(k => k.startsWith('webpackChunk'));
      for (const chunkVarName of chunkVarNames) {
        let token, source;
        try {
          window[chunkVarName].push([[Symbol()],{},(req)=>{
            for (const m of Object.values(req.c || {})) {
              try {
                const exp = m?.exports;
                const candidates = [exp?.default, exp?.Z, exp?.ZP].filter(Boolean);
                for (const c of candidates) {
                  if (typeof c?.getToken === 'function' &&
                      (typeof c?.getId === 'function' || typeof c?.getEmail === 'function')) {
                    const t = c.getToken();
                    if (isValid(t) && !token) { token = t; source = chunkVarName; }
                  }
                }
              } catch(e){}
            }
          }]);
        } catch(e){}
        if (token) { window.__dcTokenSource = 'webpack:' + source; return token; }
      }
    } catch(e){}

    // 方法2: localStorage から取得(旧バージョン向け)
    try {
      const raw = localStorage.getItem('token');
      if (raw) {
        const parsed = JSON.parse(raw);
        if (isValid(parsed)) { window.__dcTokenSource = 'localStorage'; return parsed; }
      }
    } catch(e){}

    // 方法3: 自動取得できない場合は手入力
    const manual = prompt(
      '自動でのトークン取得に失敗しました。\n' +
      '下の「🔧 開発者コンソールを開く」ボタンからConsoleタブを開き、\n' +
      'localStorage.getItem("token") を実行して出た値を貼り付けてください。\n' +
      '(表示されない場合はキャンセルしてください)'
    );
    window.__dcTokenSource = 'manual';
    const cleaned = manual ? manual.trim().replace(/^"|"$/g, '') : null;
    return isValid(cleaned) ? cleaned : null;
  }

  const token = getToken();
  if (!token) {
    alert('トークンが取得できなかったため中断しました。');
    window.__dcDeleterOpen = false;
    return;
  }
  console.log('[発言削除ツール] token source:', window.__dcTokenSource, 'preview:', token.slice(0,8) + '...' + token.slice(-4));

  const API = 'https://discord.com/api/v9';
  const headers = { authorization: token, 'content-type': 'application/json' };

  const panel = document.createElement('div');
  panel.style.cssText = 'position:fixed;inset:0;z-index:999999;background:#313338;color:#e3e5e8;font-family:-apple-system,sans-serif;font-size:14px;display:flex;flex-direction:column;';
  panel.innerHTML = `
    <style>#dc-start:disabled { opacity: 0.5; }</style>
    <div style="display:flex;justify-content:space-between;align-items:center;padding:14px 16px;background:#2b2d31;border-bottom:1px solid #1a1b1e;">
      <b style="font-size:16px;">発言削除ツール</b>
      <span id="dc-close" style="cursor:pointer;padding:6px 10px;font-size:18px;">✕</span>
    </div>
    <div style="padding:16px;overflow-y:auto;flex:1;">
      <div style="margin-bottom:14px;">
        <div style="color:#949ba4;font-size:12px;margin-bottom:6px;">対象を選択</div>
        <div style="display:flex;gap:8px;">
          <button id="dc-scope-channel" style="flex:1;padding:12px;border:none;border-radius:8px;background:#5865f2;color:#fff;">チャンネル/DM</button>
          <button id="dc-scope-guild" style="flex:1;padding:12px;border:none;border-radius:8px;background:#1a1b1e;color:#949ba4;">サーバー全体</button>
        </div>
      </div>
      <div style="margin-bottom:14px;">
        <div id="dc-id-label" style="color:#949ba4;font-size:12px;margin-bottom:6px;">サーバーを選択</div>
        <select id="dc-id-select" style="width:100%;padding:12px;border-radius:8px;border:none;background:#1a1b1e;color:#e3e5e8;font-size:14px;">
          <option value="">読み込み中...</option>
        </select>
        <details style="margin-top:6px;">
          <summary style="color:#6d7178;font-size:11px;cursor:pointer;">一覧に出ない場合: IDを直接入力</summary>
          <input id="dc-id" inputmode="numeric" placeholder="例: 123456789012345678" style="width:100%;padding:12px;border-radius:8px;border:none;background:#1a1b1e;color:#e3e5e8;font-size:14px;margin-top:6px;">
        </details>
      </div>
      <div style="margin-bottom:14px;">
        <div style="color:#949ba4;font-size:12px;margin-bottom:6px;">キーワード絞り込み(任意)</div>
        <input id="dc-keyword" placeholder="空欄でOK" style="width:100%;padding:12px;border-radius:8px;border:none;background:#1a1b1e;color:#e3e5e8;font-size:14px;">
      </div>
      <div style="margin-bottom:14px;">
        <div style="color:#949ba4;font-size:12px;margin-bottom:6px;">期間で絞り込み(任意)</div>
        <div style="display:flex;gap:8px;">
          <div style="flex:1;">
            <div style="color:#6d7178;font-size:11px;margin-bottom:4px;">この日から</div>
            <input id="dc-date-after" type="date" style="width:100%;padding:10px;border-radius:8px;border:none;background:#1a1b1e;color:#e3e5e8;font-size:13px;">
          </div>
          <div style="flex:1;">
            <div style="color:#6d7178;font-size:11px;margin-bottom:4px;">この日まで</div>
            <input id="dc-date-before" type="date" style="width:100%;padding:10px;border-radius:8px;border:none;background:#1a1b1e;color:#e3e5e8;font-size:13px;">
          </div>
        </div>
      </div>
      <button id="dc-start" disabled style="width:100%;padding:14px;background:#5865f2;color:#fff;border:none;border-radius:8px;font-size:15px;font-weight:600;">スキャン待ち</button>
      <button id="dc-eruda" style="width:100%;padding:10px;margin-top:8px;background:#1a1b1e;color:#949ba4;border:none;border-radius:8px;font-size:13px;">🔧 トークンが自動取得できない場合: 開発者コンソールを開く</button>

      <div style="display:flex;gap:8px;margin-top:16px;">
        <div style="flex:1;background:#1a1b1e;border-radius:8px;padding:10px;text-align:center;">
          <div id="dc-count-found" style="font-size:20px;font-weight:700;">0</div>
          <div style="color:#949ba4;font-size:10px;">該当</div>
        </div>
        <div style="flex:1;background:#1a1b1e;border-radius:8px;padding:10px;text-align:center;">
          <div id="dc-count-deleted" style="font-size:20px;font-weight:700;color:#3ba55d;">0</div>
          <div style="color:#949ba4;font-size:10px;">削除済み</div>
        </div>
        <div style="flex:1;background:#1a1b1e;border-radius:8px;padding:10px;text-align:center;">
          <div id="dc-count-error" style="font-size:20px;font-weight:700;color:#da373c;">0</div>
          <div style="color:#949ba4;font-size:10px;">エラー</div>
        </div>
      </div>

      <div id="dc-progress-wrap" style="margin-top:12px;display:none;">
        <div style="display:flex;justify-content:space-between;font-size:11px;color:#949ba4;margin-bottom:4px;">
          <span id="dc-progress-label">処理中...</span>
          <span id="dc-progress-pct">0%</span>
        </div>
        <div style="background:#1a1b1e;border-radius:6px;height:8px;overflow:hidden;">
          <div id="dc-progress-bar" style="background:#5865f2;height:100%;width:0%;transition:width 0.2s;"></div>
        </div>
      </div>

      <div style="color:#949ba4;font-size:12px;margin:14px 0 6px;">処理中のメッセージ</div>
      <div id="dc-msglist" style="background:#1a1b1e;border-radius:8px;max-height:220px;overflow-y:auto;"></div>

      <div style="color:#949ba4;font-size:12px;margin:14px 0 6px;">ログ</div>
      <div id="dc-log" style="background:#1a1b1e;padding:10px;border-radius:8px;font-size:12px;line-height:1.6;min-height:80px;max-height:150px;overflow-y:auto;"></div>
    </div>
  `;
  document.body.appendChild(panel);

  const countFound = panel.querySelector('#dc-count-found');
  const countDeleted = panel.querySelector('#dc-count-deleted');
  const countError = panel.querySelector('#dc-count-error');

  let currentScope = 'channel';
  const scopeGuildBtn = panel.querySelector('#dc-scope-guild');
  const scopeChannelBtn = panel.querySelector('#dc-scope-channel');
  const idLabel = panel.querySelector('#dc-id-label');
  const idSelect = panel.querySelector('#dc-id-select');
  let guildOptions = [];
  let dmOptions = [];

  function renderOptions(list, placeholder){
    if (!list) { idSelect.innerHTML = `<option value="">読み込み中...</option>`; return; }
    if (!list.length) { idSelect.innerHTML = `<option value="">(見つかりませんでした)</option>`; return; }
    idSelect.innerHTML = `<option value="">${placeholder}</option>` +
      list.map(o => `<option value="${o.id}">${o.name.replace(/</g,'&lt;')}</option>`).join('');
  }

  function setScope(s){
    currentScope = s;
    if (s === 'guild') {
      scopeGuildBtn.style.background = '#5865f2'; scopeGuildBtn.style.color = '#fff';
      scopeChannelBtn.style.background = '#1a1b1e'; scopeChannelBtn.style.color = '#949ba4';
      idLabel.textContent = 'サーバーを選択';
      renderOptions(guildOptions, 'サーバーを選んでください');
    } else {
      scopeChannelBtn.style.background = '#5865f2'; scopeChannelBtn.style.color = '#fff';
      scopeGuildBtn.style.background = '#1a1b1e'; scopeGuildBtn.style.color = '#949ba4';
      idLabel.textContent = 'DMを選択';
      renderOptions(dmOptions, 'DMを選んでください');
    }
  }
  scopeGuildBtn.onclick = () => { setScope('guild'); resetResults(); };
  scopeChannelBtn.onclick = () => { setScope('channel'); resetResults(); };

  document.getElementById('dc-close').onclick = () => { panel.remove(); window.__dcDeleterOpen = false; };

  const log = (msg) => {
    const d = document.getElementById('dc-log');
    d.innerHTML += msg + '<br>';
    d.scrollTop = d.scrollHeight;
  };

  async function loadGuildsAndDMs(){
    log(`トークン取得元: ${window.__dcTokenSource || '不明'} / 値: ${token.slice(0,8)}...${token.slice(-4)} (長さ:${token.length})`);
    log('サーバー/DM一覧を取得中...');
    try {
      const gr = await fetch(`${API}/users/@me/guilds`, { headers });
      if (!gr.ok) {
        const body = await gr.text();
        log(`サーバー一覧の取得に失敗 (status ${gr.status}): ${body.slice(0,200)}`);
        guildOptions = [];
      } else {
        const guilds = await gr.json();
        guildOptions = guilds.map(g => ({ id: g.id, name: g.name }));
        log(`サーバー一覧: ${guildOptions.length}件`);
      }
    } catch(e) { log(`サーバー一覧の取得でエラー: ${e.message}`); guildOptions = []; }

    try {
      const dr = await fetch(`${API}/users/@me/channels`, { headers });
      if (!dr.ok) {
        const body = await dr.text();
        log(`DM一覧の取得に失敗 (status ${dr.status}): ${body.slice(0,200)}`);
        dmOptions = [];
      } else {
        const dms = await dr.json();
        dmOptions = dms.map(c => {
          let name;
          if (c.type === 3) { name = c.name || (c.recipients || []).map(u => u.username).join(', ') || 'グループDM'; }
          else { name = (c.recipients && c.recipients[0]) ? c.recipients[0].username : 'DM'; }
          return { id: c.id, name };
        });
        log(`DM一覧: ${dmOptions.length}件`);
      }
    } catch(e) { log(`DM一覧の取得でエラー: ${e.message}`); dmOptions = []; }

    setScope(currentScope);
  }
  loadGuildsAndDMs();

  {
    const now = new Date();
    const todayStr = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
    document.getElementById('dc-date-before').value = todayStr;
  }

  const DISCORD_EPOCH = 1420070400000;
  function dateToSnowflake(dateStr, endOfDay){
    if (!dateStr) return null;
    const ms = new Date(dateStr + (endOfDay ? 'T23:59:59.999Z' : 'T00:00:00.000Z')).getTime();
    const rel = BigInt(ms - DISCORD_EPOCH);
    return (rel << 22n).toString();
  }

  async function myId(){
    const r = await fetch(`${API}/users/@me`, { headers });
    if (!r.ok) {
      const body = await r.text();
      throw new Error(`ログイン確認に失敗 (status ${r.status}): ${body}`);
    }
    const j = await r.json();
    return j.id;
  }

  async function searchMessages(scope, id, authorId, keyword, offset, minId, maxId){
    const params = new URLSearchParams({ author_id: authorId, offset, sort_by: 'timestamp', sort_order: 'asc' });
    if (keyword) params.set('content', keyword);
    if (minId) params.set('min_id', minId);
    if (maxId) params.set('max_id', maxId);
    const url = scope === 'guild'
      ? `${API}/guilds/${id}/messages/search?${params}`
      : `${API}/channels/${id}/messages/search?${params}`;
    const r = await fetch(url, { headers });
    if (r.status === 429) {
      const j = await r.json();
      await new Promise(res => setTimeout(res, (j.retry_after || 2) * 1000 + 500));
      return searchMessages(scope, id, authorId, keyword, offset, minId, maxId);
    }
    if (!r.ok) {
      const body = await r.text();
      throw new Error(`検索エラー (status ${r.status}): ${body}`);
    }
    return r.json();
  }

  async function deleteMessage(channelId, messageId){
    const r = await fetch(`${API}/channels/${channelId}/messages/${messageId}`, { method: 'DELETE', headers });
    if (r.status === 429) {
      const j = await r.json();
      await new Promise(res => setTimeout(res, (j.retry_after || 2) * 1000 + 500));
      return deleteMessage(channelId, messageId);
    }
    if (!r.ok) {
      const body = await r.text();
      let code;
      try { code = JSON.parse(body).code; } catch(e){}
      if (code === 50021) { return 'skip'; }
      log(`　→削除エラー (status ${r.status}): ${body.slice(0,200)}`);
      return false;
    }
    return true;
  }

  const idInput = document.getElementById('dc-id');
  idInput.addEventListener('input', () => {
    idInput.value = idInput.value.replace(/[^0-9]/g, '');
  });

  document.getElementById('dc-eruda').onclick = () => {
    if (window.eruda) { window.eruda.show(); return; }
    const s = document.createElement('script');
    s.src = 'https://cdn.jsdelivr.net/npm/eruda';
    s.onload = () => { window.eruda.init(); window.eruda.show(); log('コンソールを開きました。Consoleタブで <code>localStorage.getItem("token")</code> を実行し、表示された値(前後の引用符は除く)をコピーしてください。'); };
    document.body.appendChild(s);
  };

  const msgList = document.getElementById('dc-msglist');
  const progressWrap = document.getElementById('dc-progress-wrap');
  const progressBar = document.getElementById('dc-progress-bar');
  const progressPct = document.getElementById('dc-progress-pct');
  const progressLabel = document.getElementById('dc-progress-label');
  const startBtn = document.getElementById('dc-start');

  let isRunning = false;
  let stopRequested = false;
  let isScanning = false;
  let scannedMessages = []; // {id, channel_id, content}
  const seenMsgIds = new Set();

  function addMsgRow(msg){
    if (seenMsgIds.has(msg.id)) { return document.getElementById(`dc-row-${msg.id}`); }
    seenMsgIds.add(msg.id);
    const row = document.createElement('div');
    row.id = `dc-row-${msg.id}`;
    row.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:8px;padding:8px 10px;border-bottom:1px solid #26272b;font-size:12px;';
    const hasAttachment = Array.isArray(msg.attachments) && msg.attachments.length > 0;
    const rawPreview = msg.content || (hasAttachment ? `📎 添付ファイル (${msg.attachments.length}件)` : '(本文なし)');
    const preview = rawPreview.slice(0, 40).replace(/</g,'&lt;');
    row.innerHTML = `<span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#dbdee1;">${preview}</span><span id="dc-status-${msg.id}" style="flex-shrink:0;color:#949ba4;">⏳ 待機中</span>`;
    msgList.appendChild(row);
    row.scrollIntoView({ block: 'nearest' });
    return row;
  }
  function setMsgStatus(msgId, text, color){
    const el = document.getElementById(`dc-status-${msgId}`);
    if (el) { el.textContent = text; if (color) el.style.color = color; }
  }

  function resetResults(){
    msgList.innerHTML = '';
    seenMsgIds.clear();
    scannedMessages = [];
    countFound.textContent = '0'; countDeleted.textContent = '0'; countError.textContent = '0';
    progressWrap.style.display = 'none';
    progressBar.style.width = '0%';
    progressPct.textContent = '0%';
    startBtn.disabled = true;
    startBtn.textContent = 'スキャン待ち';
  }

  async function runScan(){
    if (isScanning || isRunning) return;
    const scope = currentScope;
    const id = (idSelect.value || idInput.value.trim()).trim();
    if (!id || id.length < 15) { resetResults(); return; }

    resetResults();
    isScanning = true;
    startBtn.disabled = true;
    startBtn.textContent = 'スキャン中...';
    progressWrap.style.display = 'block';
    progressLabel.textContent = 'スキャン中...';

    const keyword = document.getElementById('dc-keyword').value.trim();
    const minId = dateToSnowflake(document.getElementById('dc-date-after').value, false);
    const maxId = dateToSnowflake(document.getElementById('dc-date-before').value, true);

    try {
      log(`スキャン開始 (対象: ${scope === 'guild' ? 'サーバー全体' : 'チャンネル'} / ID: ${id})`);
      const authorId = await myId();

      let offset = 0, found = 0, total = 0;
      while (true) {
        const data = await searchMessages(scope, id, authorId, keyword, offset, minId, maxId);
        const messages = data.messages || [];
        total = data.total_results || 0;
        if (!messages.length) break;

        for (const group of messages) {
          const msg = group[0];
          if (msg.type === 0 || msg.type === 19) {
            found++; countFound.textContent = found;
            scannedMessages.push(msg);
            addMsgRow(msg);
          }
          const pct = total ? Math.min(100, Math.round(((offset + messages.indexOf(group) + 1) / total) * 100)) : 0;
          progressBar.style.width = pct + '%';
          progressPct.textContent = pct + '%';
        }
        offset += messages.length;
        if (offset >= total) break;
        await new Promise(res => setTimeout(res, 1200));
      }

      scannedMessages.sort((a, b) => { const x = BigInt(a.id), y = BigInt(b.id); return x < y ? -1 : x > y ? 1 : 0; });
      msgList.innerHTML = ''; seenMsgIds.clear();
      for (const m of scannedMessages) addMsgRow(m);
      msgList.scrollTop = 0;
      progressLabel.textContent = `スキャン完了: ${found}件(古い順)`;
      progressBar.style.width = '100%';
      progressPct.textContent = '100%';
      log(`<b>スキャン完了</b> 削除可能なメッセージ: ${found}件`);

      startBtn.disabled = found === 0;
      startBtn.textContent = found === 0 ? '対象なし' : '削除開始';
    } catch (e) {
      log(`<b style="color:#da373c;">スキャンエラー: ${e.message}</b>`);
      progressLabel.textContent = 'スキャン失敗';
      startBtn.textContent = '再スキャンしてください';
      startBtn.disabled = true;
    }
    isScanning = false;
  }

  // サーバー/DM選択が変わったら自動でスキャン開始
  idSelect.addEventListener('change', runScan);
  document.getElementById('dc-keyword').addEventListener('change', runScan);
  document.getElementById('dc-date-after').addEventListener('change', runScan);
  document.getElementById('dc-date-before').addEventListener('change', runScan);
  idInput.addEventListener('change', () => { if (idInput.value.trim().length >= 15) runScan(); });

  startBtn.onclick = async () => {
    if (isRunning) {
      stopRequested = true;
      startBtn.textContent = '停止中...';
      startBtn.disabled = true;
      return;
    }
    if (isScanning) { alert('スキャン中です。完了までお待ちください。'); return; }
    if (!scannedMessages.length) { alert('削除対象がありません。サーバー/DMを選択して自動スキャンが完了するのを待ってください。'); return; }


    isRunning = true;
    stopRequested = false;
    startBtn.disabled = false;
    startBtn.textContent = '■ 停止';
    startBtn.style.background = '#da373c';
    progressLabel.textContent = '削除中...';

    let deleted = 0, errors = 0;
    const totalToDelete = scannedMessages.length;

    for (let i = 0; i < scannedMessages.length; i++) {
      if (stopRequested) break;
      const msg = scannedMessages[i];
      setMsgStatus(msg.id, '🗑 削除中...', '#f0b132');

      const pct = Math.round(((i + 1) / totalToDelete) * 100);
      progressBar.style.width = pct + '%';
      progressPct.textContent = pct + '%';

      const result = await deleteMessage(msg.channel_id, msg.id);
      if (result === 'skip') {
        setMsgStatus(msg.id, '⏭ 削除不可', '#949ba4');
      } else if (result) {
        deleted++; countDeleted.textContent = deleted; setMsgStatus(msg.id, '✅ 削除済み', '#3ba55d');
      } else {
        errors++; countError.textContent = errors; setMsgStatus(msg.id, '❌ エラー', '#da373c');
      }
      await new Promise(res => setTimeout(res, 1500));
    }

    if (stopRequested) {
      progressLabel.textContent = '停止しました';
      log(`<b>停止</b> 削除:${deleted}件 / エラー:${errors}件`);
    } else {
      progressLabel.textContent = '完了';
      log(`<b>完了</b> 削除:${deleted}件 / エラー:${errors}件`);
    }

    isRunning = false;
    stopRequested = false;
    startBtn.disabled = false;
    startBtn.textContent = '削除開始';
    startBtn.style.background = '#5865f2';
  };

  } catch (fatalError) {
    window.__dcDeleterOpen = false;
    alert('予期しないエラーで停止しました:\n' + (fatalError && fatalError.message ? fatalError.message : fatalError) + '\n\n(スタック: ' + (fatalError && fatalError.stack ? fatalError.stack.slice(0,300) : '不明') + ')');
    console.error('[発言削除ツール] fatal error:', fatalError);
  }
  }

})();
